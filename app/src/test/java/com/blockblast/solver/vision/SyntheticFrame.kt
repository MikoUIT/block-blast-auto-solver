package com.blockblast.solver.vision

/**
 * Builds synthetic Block Blast-like frames for tests. This lets us verify the recognition pipeline
 * without a device or a real screenshot: we control the exact board and pieces, then assert the
 * recogniser recovers them.
 *
 * Two palettes are provided — a dark theme and a light theme — so a test can prove the recogniser is
 * colour-agnostic, which is the whole point of working on luminance alone.
 */
object SyntheticFrame {

    data class Palette(
        val background: Int,
        val boardBg: Int,
        val gridLine: Int,
        val blockFilled: Int,
        val trayBg: Int,
    ) {
        companion object {
            val DARK = Palette(
                background = 20,
                boardBg = 35,
                gridLine = 70,
                blockFilled = 210,
                trayBg = 25,
            )
            val LIGHT = Palette(
                background = 235,
                boardBg = 245,
                gridLine = 200,
                blockFilled = 60,
                trayBg = 240,
            )
        }
    }

    data class Layout(
        val left: Int,
        val top: Int,
        val size: Int, // board side in pixels (square)
    ) {
        val cell: Int get() = size / 8
    }

    /**
     * Renders a frame with the given [board] (row-major booleans, 64 entries) and [pieces] in the
     * tray. Pieces are drawn as [pieceCells] lists per slot, where each entry is the filled cells of
     * a piece in its own grid.
     */
    fun render(
        width: Int,
        height: Int,
        layout: Layout,
        board: BooleanArray,
        pieces: List<List<Pair<Int, Int>>>,
        palette: Palette = Palette.DARK,
    ): GrayImage {
        val pixels = IntArray(width * height) { palette.background }
        fillRect(pixels, width, layout.left, layout.top, layout.size, layout.size, palette.boardBg)
        val cell = layout.cell

        // Grid lines.
        for (i in 0..8) {
            val x = layout.left + i * cell
            val y = layout.top + i * cell
            drawRectOutline(pixels, width, x - 1, layout.top, x + 1, layout.top + layout.size, palette.gridLine)
            drawRectOutline(pixels, width, layout.left, y - 1, layout.left + layout.size, y + 1, palette.gridLine)
        }

        // Filled cells, inset slightly to leave a visible gap (like real blocks).
        for (r in 0 until 8) {
            for (c in 0 until 8) {
                if (!board[r * 8 + c]) continue
                val x = layout.left + c * cell + 3
                val y = layout.top + r * cell + 3
                fillRect(pixels, width, x, y, cell - 6, cell - 6, palette.blockFilled)
            }
        }

        // Tray pieces, laid out in thirds.
        val trayTop = layout.top + layout.size + 40
        val slotW = width / 3
        pieces.forEachIndexed { slot, cells ->
            if (cells.isEmpty()) return@forEachIndexed
            val maxR = cells.maxOf { it.first }
            val maxC = cells.maxOf { it.second }
            val pW = (maxC + 1) * cell
            val pH = (maxR + 1) * cell
            val originX = slot * slotW + (slotW - pW) / 2
            val originY = trayTop
            for ((r, c) in cells) {
                val x = originX + c * cell + 2
                val y = originY + r * cell + 2
                fillRect(pixels, width, x, y, cell - 4, cell - 4, palette.blockFilled)
            }
        }
        return GrayImage(width, height, pixels)
    }

    private fun fillRect(pixels: IntArray, width: Int, x: Int, y: Int, w: Int, h: Int, value: Int) {
        for (yy in y until y + h) {
            if (yy < 0) continue
            val base = yy * width
            for (xx in x until x + w) {
                if (xx < 0 || xx >= width) continue
                val idx = base + xx
                if (idx in pixels.indices) pixels[idx] = value
            }
        }
    }

    private fun drawRectOutline(pixels: IntArray, width: Int, x1: Int, y1: Int, x2: Int, y2: Int, value: Int) {
        for (x in x1 until x2) {
            setSafe(pixels, width, x, y1, value)
            setSafe(pixels, width, x, y2 - 1, value)
        }
        for (y in y1 until y2) {
            setSafe(pixels, width, x1, y, value)
            setSafe(pixels, width, x2 - 1, y, value)
        }
    }

    private fun setSafe(pixels: IntArray, width: Int, x: Int, y: Int, value: Int) {
        if (x < 0 || y < 0) return
        val idx = y * width + x
        if (idx in pixels.indices) pixels[idx] = value
    }
}
