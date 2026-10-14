package com.blockblast.solver.model

/**
 * A concrete way to drop one [piece] onto the board at a given top-left anchor.
 *
 * [cells] holds the board cell indices that the piece would occupy. [fullRows] and [fullCols] are
 * the board rows/columns that the placement completes — precomputed here so the search never has
 * to rescan the board when scoring a move.
 */
data class Placement(
    val piece: Piece,
    val anchorRow: Int,
    val anchorCol: Int,
    val cells: IntArray,
    val fullRows: IntArray,
    val fullCols: IntArray,
) {
    /** Number of rows/cols this placement clears — used by the engine and the debug overlay. */
    val clears: Int get() = fullRows.size + fullCols.size

    val cellCount: Int get() = cells.size

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Placement) return false
        return piece === other.piece && anchorRow == other.anchorRow && anchorCol == other.anchorCol
    }

    override fun hashCode(): Int = (piece.hashCode() * 31 + anchorRow) * 31 + anchorCol

    override fun toString(): String =
        "${piece.sourceId} @ ($anchorRow,$anchorCol) clears=${clears}"

    companion object {
        /** Board cell index for a row/column pair. */
        fun index(row: Int, col: Int): Int = row * Board.SIZE + col
    }
}
