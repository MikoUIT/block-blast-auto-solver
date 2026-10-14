package com.blockblast.solver.diag

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Base64
import com.blockblast.solver.core.SolverRuntime
import com.blockblast.solver.vision.GrayImage
import java.io.ByteArrayOutputStream
import kotlin.math.max

/**
 * Renders a captured frame two ways for a bug report: the plain luminance image, and the same image
 * with the recogniser's interpretation drawn on top (detected grid, occupied cells, piece boxes).
 *
 * Seeing those two side by side is what turns "the solver is wrong" into "the grid detector latched
 * onto a button instead of the board" — the failure mode is nearly always geometric, not scoring.
 * Both images are downscaled and JPEG-encoded so they travel inline in a GitHub issue.
 */
object SnapshotRenderer {

    private const val MAX_WIDTH = 720
    private const val JPEG_QUALITY = 78

    /** A rendered pair for one frame. */
    data class Frame(
        val plainJpegBase64: String,
        val markedJpegBase64: String,
        val label: String,
    )

    /** Renders the plain and annotated views of [gray] with [snapshot]'s interpretation. */
    fun render(gray: GrayImage, snapshot: SolverRuntime.Snapshot, label: String): Frame {
        val scale = if (gray.width > MAX_WIDTH) MAX_WIDTH.toFloat() / gray.width else 1f
        val outW = max(1, (gray.width * scale).toInt())
        val outH = max(1, (gray.height * scale).toInt())

        val plain = grayToBitmap(gray, outW, outH)
        val marked = plain.copy(Bitmap.Config.ARGB_8888, true)
        drawOverlay(marked, snapshot, scale)

        return Frame(
            plainJpegBase64 = encode(plain),
            markedJpegBase64 = encode(marked),
            label = label,
        )
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

    private fun drawOverlay(bitmap: Bitmap, snapshot: SolverRuntime.Snapshot, scale: Float) {
        val canvas = Canvas(bitmap)
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = Color.parseColor("#00E5FF")
        }
        val filled = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#66FF4081") // occupied cell reading
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
                    canvas.drawRect(
                        b[0] * scale,
                        b[1] * scale,
                        b[2] * scale,
                        b[3] * scale,
                        filled,
                    )
                }
            }
        }
        for (p in snapshot.detectedPieces) {
            canvas.drawRect(p.left * scale, p.top * scale, p.right * scale, p.bottom * scale, pieceBox)
        }

        // A short caption so the frame is self-describing when read later in the issue.
        val caption = buildString {
            append("frame ${snapshot.frameId}  ${snapshot.solveMillis}ms")
            append("\nfilled ${snapshot.board.filledCount()}")
            append("  pieces ${snapshot.recognizedPieces.size}")
            append("/matched ${snapshot.piecesMatched}")
            grid?.let { append("\ngrid ${it.left},${it.top} ${it.width}x${it.height}") }
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
}
