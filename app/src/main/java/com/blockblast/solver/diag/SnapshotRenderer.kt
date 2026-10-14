package com.blockblast.solver.diag

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Base64
import com.blockblast.solver.core.SolverRuntime
import com.blockblast.solver.vision.GrayImage
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.max

/**
 * Renders one captured frame as several *different* views, each showing a distinct stage or a
 * genuinely different piece of information — not the same picture recoloured:
 *
 *  1. **colour**    — the untouched source frame (theme, colours, effects).
 *  2. **grey**      — the luminance the pipeline actually works on.
 *  3. **binary**    — the adaptive threshold result: white = "filled", black = "background". This is
 *                     what the cell recogniser effectively sees, so a wrong threshold is obvious.
 *  4. **binaryFixed** — the same frame at a fixed threshold, for contrast: it shows whether the
 *                     adaptive choice is helping or hurting.
 *  5. **marked**    — the recognised interpretation drawn over the colour frame (grid box, occupied
 *                     cells, piece boxes, caption).
 *
 * Every view is downscaled and JPEG-encoded so it travels inline in a report.
 */
object SnapshotRenderer {

    private const val MAX_WIDTH = 720
    private const val JPEG_QUALITY = 78
    private const val FIXED_THRESHOLD = 128

    data class View(val name: String, val jpegBase64: String, val ext: String = "jpg")

    /** All rendered views for one frame. */
    data class Frame(
        val label: String,
        val views: List<View>,
    ) {
        /** Back-compat accessors for the local-server JSON, which expects these two keys. */
        val plainJpegBase64: String get() = views.firstOrNull { it.name == "grey" }?.jpegBase64 ?: ""
        val markedJpegBase64: String get() = views.firstOrNull { it.name == "marked" }?.jpegBase64 ?: ""
    }

    fun render(gray: GrayImage, snapshot: SolverRuntime.Snapshot, label: String): Frame {
        val scale = if (gray.width > MAX_WIDTH) MAX_WIDTH.toFloat() / gray.width else 1f
        val outW = max(1, (gray.width * scale).toInt())
        val outH = max(1, (gray.height * scale).toInt())

        val views = ArrayList<View>(5)

        // 1. Colour source (only if we kept the frame).
        snapshot.colorArgb?.let { argb ->
            views.add(View("colour", encode(argbToBitmap(argb, gray.width, gray.height, outW, outH))))
        }

        // 2. Grey luminance. Encoded as lossless PNG: for tuning the detector I need the exact
        //    pixels, and JPEG smoothing measurably shifts the autocorrelation pitch.
        views.add(View("grey", encodePng(grayToBitmap(gray, outW, outH)), ext = "png"))

        // 3. Adaptive binary: a pixel is "filled" when it differs from the board background by more
        //    than the margin the recogniser used. This is the exact decision the cells go through.
        //    PNG, because JPEG would blur the hard black/white edges that matter here.
        views.add(
            View(
                "binary",
                encodePng(binaryBitmap(gray, outW, outH) { luma ->
                    abs(luma - snapshot.backgroundLuma) > snapshot.fillThreshold
                }),
                ext = "png",
            ),
        )

        // 4. Fixed-threshold binary, for contrast against the adaptive one.
        views.add(
            View(
                "binaryFixed",
                encodePng(binaryBitmap(gray, outW, outH) { luma -> luma >= FIXED_THRESHOLD }),
                ext = "png",
            ),
        )

        // 5. Recognition overlay on top of the colour (or grey) frame.
        val markedBase = snapshot.colorArgb?.let { argbToBitmap(it, gray.width, gray.height, outW, outH) }
            ?: grayToBitmap(gray, outW, outH)
        drawOverlay(markedBase, snapshot, scale)
        views.add(View("marked", encode(markedBase)))

        return Frame(label = label, views = views)
    }

    private fun argbToBitmap(argb: IntArray, w: Int, h: Int, outW: Int, outH: Int): Bitmap {
        val full = Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888)
        if (outW == w && outH == h) return full
        val scaled = Bitmap.createScaledBitmap(full, outW, outH, true)
        if (scaled !== full) full.recycle()
        return scaled
    }

    private fun grayToBitmap(gray: GrayImage, outW: Int, outH: Int): Bitmap {
        val pixels = IntArray(gray.width * gray.height)
        for (i in pixels.indices) {
            val v = gray.pixels[i].coerceIn(0, 255)
            pixels[i] = Color.rgb(v, v, v)
        }
        val full = Bitmap.createBitmap(pixels, gray.width, gray.height, Bitmap.Config.ARGB_8888)
        if (outW == gray.width && outH == gray.height) return full
        val scaled = Bitmap.createScaledBitmap(full, outW, outH, true)
        if (scaled !== full) full.recycle()
        return scaled
    }

    /** Builds a pure black/white bitmap: white where [filled] is true, black otherwise. */
    private fun binaryBitmap(
        gray: GrayImage,
        outW: Int,
        outH: Int,
        filled: (Int) -> Boolean,
    ): Bitmap {
        val pixels = IntArray(gray.width * gray.height)
        for (i in pixels.indices) {
            pixels[i] = if (filled(gray.pixels[i])) Color.WHITE else Color.BLACK
        }
        val full = Bitmap.createBitmap(pixels, gray.width, gray.height, Bitmap.Config.ARGB_8888)
        if (outW == gray.width && outH == gray.height) return full
        val scaled = Bitmap.createScaledBitmap(full, outW, outH, false)
        if (scaled !== full) full.recycle()
        return scaled
    }

    private fun drawOverlay(bitmap: Bitmap, snapshot: SolverRuntime.Snapshot, scale: Float) {
        val canvas = Canvas(bitmap)
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = Color.parseColor("#00E5FF")
        }
        val filled = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#66FF4081")
        }
        val pieceBox = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = Color.parseColor("#FFEB3B")
        }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 13f
            setShadowLayer(3f, 0f, 1f, Color.BLACK)
        }

        val grid = snapshot.grid
        if (grid != null) {
            canvas.drawRect(
                grid.left * scale,
                grid.top * scale,
                grid.right * scale,
                grid.bottom * scale,
                line,
            )
            for (r in 0 until 8) {
                for (c in 0 until 8) {
                    if (!snapshot.board.isFilled(r, c)) continue
                    val b = grid.cellBounds(r, c)
                    canvas.drawRect(b[0] * scale, b[1] * scale, b[2] * scale, b[3] * scale, filled)
                }
            }
        }
        for (p in snapshot.detectedPieces) {
            canvas.drawRect(p.left * scale, p.top * scale, p.right * scale, p.bottom * scale, pieceBox)
        }

        val caption = buildString {
            append("frame ${snapshot.frameId}  ${snapshot.solveMillis}ms")
            append("\nfilled ${snapshot.board.filledCount()}  pieces ${snapshot.recognizedPieces.size}/${snapshot.piecesMatched}")
            grid?.let { append("\ngrid ${it.left},${it.top} ${it.width}x${it.height}") }
            append("\nbg luma ${"%.0f".format(snapshot.backgroundLuma)}  margin ${"%.0f".format(snapshot.fillThreshold)}")
        }
        var y = 18f
        for (row in caption.split('\n')) {
            canvas.drawText(row, 8f, y, text)
            y += text.textSize + 4f
        }
    }

    private fun encode(bitmap: Bitmap): String {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
        bitmap.recycle()
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }

    private fun encodePng(bitmap: Bitmap): String {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        bitmap.recycle()
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }
}
