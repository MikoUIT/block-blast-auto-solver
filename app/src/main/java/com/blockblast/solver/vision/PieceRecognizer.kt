package com.blockblast.solver.vision

import com.blockblast.solver.model.Piece
import com.blockblast.solver.model.PieceCatalog

/**
 * Recognises the three offered pieces in the tray beneath the board.
 *
 * Each piece is a cluster of block-sized squares. The tray is split into three horizontal thirds;
 * within each third we find the cluster of filled pixels, take its bounding box, then quantise that
 * box into a grid whose cell size matches the board cell size. The occupied grid cells become the
 * piece mask, which is then matched against [PieceCatalog].
 *
 * Working from the bounding box rather than a fixed layout means a small 1x1 piece and a 5-cell
 * piece are handled by the same code — only the box differs.
 */
class PieceRecognizer(
    private val config: PieceRecognizerConfig = PieceRecognizerConfig.DEFAULT,
) {
    /** A recognised piece together with the pixel box it came from (for the debug overlay). */
    data class RecognizedPiece(
        val piece: Piece,
        val slot: Int,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val matched: Boolean,
    )

    /**
     * @param trayTop  first row of the tray region (below the board)
     * @param cellPx   board cell size in pixels, used to size the piece grid
     */
    fun recognize(image: GrayImage, trayTop: Int, cellPx: Double): List<RecognizedPiece> {
        val trayBottom = image.height
        if (trayTop >= trayBottom) return emptyList()

        // Estimate the tray background from the region and isolate filled pixels.
        val background = image.meanRect(0, trayTop, image.width, trayBottom)
        val threshold = maxOf(config.minAbsoluteMargin, background * config.relativeMargin)

        val slotWidth = image.width / SLOT_COUNT
        val results = ArrayList<RecognizedPiece>(SLOT_COUNT)
        for (slot in 0 until SLOT_COUNT) {
            val slotLeft = slot * slotWidth
            val slotRight = if (slot == SLOT_COUNT - 1) image.width else slotLeft + slotWidth
            val box = contentBox(image, slotLeft, slotRight, trayTop, trayBottom, background, threshold)
            if (box == null) continue
            val (l, t, r, b) = box
            val grid = quantize(image, l, t, r, b, cellPx, background, threshold)
            if (grid == null) continue
            val cells = grid.cells
            if (cells.isEmpty()) continue
            val matched = PieceCatalog.match(grid.rows, grid.cols, cells)
            val piece = matched ?: Piece.fromGrid("slot$slot", toGrid(grid.rows, grid.cols, cells))
            results.add(
                RecognizedPiece(
                    piece = piece,
                    slot = slot,
                    left = l,
                    top = t,
                    right = r,
                    bottom = b,
                    matched = matched != null,
                )
            )
        }
        return results
    }

    private data class Box(val l: Int, val t: Int, val r: Int, val b: Int)

    private data class QuantizedGrid(val rows: Int, val cols: Int, val cells: List<Pair<Int, Int>>)

    private fun contentBox(
        image: GrayImage,
        left: Int,
        right: Int,
        top: Int,
        bottom: Int,
        background: Double,
        threshold: Double,
    ): Box? {
        var minX = right
        var minY = bottom
        var maxX = left - 1
        var maxY = top - 1
        for (y in top until bottom) {
            for (x in left until right) {
                if (kotlin.math.abs(image[x, y] - background) > threshold) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < minX || maxY < minY) return null
        // Reject dust: a real piece spans at least about one cell in each direction.
        if (maxX - minX < config.minBoxFraction && maxY - minY < config.minBoxFraction) return null
        return Box(minX, minY, maxX + 1, maxY + 1)
    }

    private fun quantize(
        image: GrayImage,
        l: Int,
        t: Int,
        r: Int,
        b: Int,
        cellPx: Double,
        background: Double,
        threshold: Double,
    ): QuantizedGrid? {
        if (cellPx <= 1.0) return null
        val cols = kotlin.math.round((r - l) / cellPx).toInt().coerceIn(1, 5)
        val rows = kotlin.math.round((b - t) / cellPx).toInt().coerceIn(1, 5)
        val stepX = (r - l).toDouble() / cols
        val stepY = (b - t).toDouble() / rows
        val cells = mutableListOf<Pair<Int, Int>>()
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val cx = (l + (col + 0.5) * stepX).toInt()
                val cy = (t + (row + 0.5) * stepY).toInt()
                val sample = image.meanRect(
                    left = (l + col * stepX + stepX * config.cellInset).toInt(),
                    top = (t + row * stepY + stepY * config.cellInset).toInt(),
                    right = (l + (col + 1) * stepX - stepX * config.cellInset).toInt(),
                    bottom = (t + (row + 1) * stepY - stepY * config.cellInset).toInt(),
                )
                val filled = kotlin.math.abs(sample - background) > threshold ||
                    (cy in 0 until image.height && cx in 0 until image.width &&
                        kotlin.math.abs(image[cx, cy] - background) > threshold)
                if (filled) cells.add(row to col)
            }
        }
        if (cells.isEmpty()) return null
        return QuantizedGrid(rows, cols, cells)
    }

    private fun toGrid(rows: Int, cols: Int, cells: List<Pair<Int, Int>>): List<List<Boolean>> {
        val grid = MutableList(rows) { MutableList(cols) { false } }
        for ((r, c) in cells) if (r < rows && c < cols) grid[r][c] = true
        return grid
    }

    private companion object {
        const val SLOT_COUNT = 3
    }
}

/** Parameters for [PieceRecognizer]. */
data class PieceRecognizerConfig(
    /** Absolute minimum fill/background separation for a tray pixel to count as content. */
    val minAbsoluteMargin: Double = 16.0,
    /** Relative separation, as a fraction of the tray background luminance. */
    val relativeMargin: Double = 0.12,
    /** Minimum content-box span (in pixels) below which we treat the slot as empty. */
    val minBoxFraction: Int = 8,
    /** Fraction carved off each quantised cell before sampling, to skip block borders. */
    val cellInset: Double = 0.25,
) {
    companion object {
        val DEFAULT = PieceRecognizerConfig()
    }
}
