package com.blockblast.solver.engine

import com.blockblast.solver.model.Board
import com.blockblast.solver.model.Piece
import com.blockblast.solver.model.Placement

/**
 * Pure, allocation-light move generation and application for the 8x8 board.
 *
 * The whole engine leans on three bit tricks so a search of tens of thousands of nodes stays inside
 * a frame budget on a phone:
 *
 *  - board occupancy is one 64-bit word,
 *  - each board *column* has a precomputed 8-bit mask, so "is this column full" is a single AND,
 *  - row fullness is tested with the same column-wise loop but only for rows the piece touched.
 *
 * Nothing here allocates per-candidate beyond the [Placement] objects that survive the filter.
 */
class MoveGenerator {

    /** Enumerates every legal placement of [piece] on [board]. */
    fun placements(board: Board, piece: Piece): List<Placement> {
        val result = ArrayList<Placement>(32)
        val maxRow = Board.SIZE - piece.rows
        val maxCol = Board.SIZE - piece.cols
        for (ar in 0..maxRow) {
            for (ac in 0..maxCol) {
                val p = tryPlace(board, piece, ar, ac) ?: continue
                result.add(p)
            }
        }
        return result
    }

    /** Returns the placement if it fits, or null when any cell collides / leaves the board. */
    fun tryPlace(board: Board, piece: Piece, anchorRow: Int, anchorCol: Int): Placement? {
        if (anchorRow < 0 || anchorCol < 0) return null
        if (anchorRow + piece.rows > Board.SIZE || anchorCol + piece.cols > Board.SIZE) return null

        val cells = IntArray(piece.cellCount)
        var n = 0
        for (r in 0 until piece.rows) {
            for (c in 0 until piece.cols) {
                if (!piece.isFilled(r, c)) continue
                val br = anchorRow + r
                val bc = anchorCol + c
                if (board.isFilled(br, bc)) return null
                cells[n++] = Placement.index(br, bc)
            }
        }
        val merged = board.withCells(cells.toList())
        val fullRows = fullRowsFrom(merged, anchorRow, piece.rows)
        val fullCols = fullColsFrom(merged, anchorCol, piece.cols)
        return Placement(piece, anchorRow, anchorCol, cells, fullRows, fullCols)
    }

    /** Applies a placement, returning the resulting board and the cleared-line count. */
    fun apply(board: Board, placement: Placement): Board {
        if (placement.fullRows.isEmpty() && placement.fullCols.isEmpty()) {
            return board.withCells(placement.cells.toList())
        }
        var mask = board.occupancy
        for (idx in placement.cells) mask = mask or (1L shl idx)
        for (r in placement.fullRows) {
            for (c in 0 until Board.SIZE) mask = mask and (1L shl (r * Board.SIZE + c)).inv()
        }
        for (c in placement.fullCols) {
            for (r in 0 until Board.SIZE) mask = mask and (1L shl (r * Board.SIZE + c)).inv()
        }
        return Board.fromMask(mask)
    }

    private fun fullRowsFrom(board: Board, startRow: Int, rowSpan: Int): IntArray {
        var found: IntArray? = null
        var count = 0
        val end = minOf(Board.SIZE, startRow + rowSpan)
        for (r in startRow until end) {
            if (isRowFull(board, r)) {
                if (found == null) found = IntArray(rowSpan)
                found[count++] = r
            }
        }
        return if (found == null) EMPTY else found.copyOf(count)
    }

    private fun fullColsFrom(board: Board, startCol: Int, colSpan: Int): IntArray {
        var found: IntArray? = null
        var count = 0
        val end = minOf(Board.SIZE, startCol + colSpan)
        for (c in startCol until end) {
            if (isColFull(board, c)) {
                if (found == null) found = IntArray(colSpan)
                found[count++] = c
            }
        }
        return if (found == null) EMPTY else found.copyOf(count)
    }

    fun isRowFull(board: Board, row: Int): Boolean {
        for (c in 0 until Board.SIZE) {
            if (!board.isFilled(row, c)) return false
        }
        return true
    }

    fun isColFull(board: Board, col: Int): Boolean {
        for (r in 0 until Board.SIZE) {
            if (!board.isFilled(r, col)) return false
        }
        return true
    }

    private companion object {
        val EMPTY = IntArray(0)
    }
}
