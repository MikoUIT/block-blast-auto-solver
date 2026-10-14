package com.blockblast.solver.model

/**
 * A single block piece, stored as a compact row-major bitmask of its bounding box.
 *
 * The mask is normalised: empty leading rows/columns are trimmed, so [rows] and [cols] are the
 * tight bounding box of the filled cells. Cells are addressed as `row * cols + col`, where `row`
 * grows downward and `col` grows rightward.
 *
 * Pieces are immutable value objects so they can be freely shared and compared. A piece carries no
 * position — where it sits on the board is decided by the caller (see [Placement]).
 */
data class Piece(
    val sourceId: String,
    val rows: Int,
    val cols: Int,
    val mask: Int,
    /** Number of filled cells. Kept explicit because it drives scoring and search pruning. */
    val cellCount: Int,
) {
    /** True when the cell at (r, c) is filled. Out-of-bounds reads are treated as empty. */
    fun isFilled(r: Int, c: Int): Boolean {
        if (r < 0 || c < 0 || r >= rows || c >= cols) return false
        return (mask ushr (r * cols + c)) and 1 == 1
    }

    /** Column heights / widths used by the placement engine. */
    val width: Int get() = cols
    val height: Int get() = rows

    override fun toString(): String = buildString {
        append(sourceId).append('\n')
        for (r in 0 until rows) {
            for (c in 0 until cols) append(if (isFilled(r, c)) '#' else '.')
            if (r < rows - 1) append('\n')
        }
    }

    companion object {
        /** Builds a normalised piece from an ASCII grid where any non-space, non-dot char is a cell. */
        fun fromAscii(sourceId: String, art: String): Piece {
            val lines = art.trim('\n').lines()
            val grid = lines.map { line -> line.map { ch -> ch != ' ' && ch != '.' } }
            return fromGrid(sourceId, grid)
        }

        /** Builds a normalised piece from a boolean grid, trimming empty borders. */
        fun fromGrid(sourceId: String, grid: List<List<Boolean>>): Piece {
            var top = grid.size
            var bottom = -1
            var left = grid.firstOrNull()?.size ?: 0
            var right = -1
            var cells = 0
            for (r in grid.indices) {
                for (c in grid[r].indices) {
                    if (grid[r][c]) {
                        if (r < top) top = r
                        if (r > bottom) bottom = r
                        if (c < left) left = c
                        if (c > right) right = c
                        cells++
                    }
                }
            }
            if (cells == 0) {
                // An empty piece never occurs in the game, but represent it safely.
                return Piece(sourceId, 0, 0, 0, 0)
            }
            val rows = bottom - top + 1
            val cols = right - left + 1
            var mask = 0
            for (r in top..bottom) {
                for (c in left..right) {
                    if (grid[r][c]) {
                        mask = mask or (1 shl ((r - top) * cols + (c - left)))
                    }
                }
            }
            return Piece(sourceId, rows, cols, mask, cells)
        }
    }
}
