package com.blockblast.solver.model

/**
 * The 8x8 playfield. Occupancy is a 64-bit mask; bit index is `row * SIZE + col`.
 *
 * The board is immutable — every [place] returns a new instance. This keeps the search tree cheap
 * to branch and impossible to corrupt, and lets a whole subtree share one board without copying
 * arrays.
 */
@JvmInline
value class Board private constructor(val occupancy: Long) {

    val isEmpty: Boolean get() = occupancy == 0L

    fun isFilled(r: Int, c: Int): Boolean = (occupancy ushr (r * SIZE + c)) and 1L == 1L

    fun filledCount(): Int = java.lang.Long.bitCount(occupancy)

    /** Returns a new board with every cell of [cells] set. */
    fun withCells(cells: Collection<Int>): Board {
        var next = occupancy
        for (idx in cells) next = next or (1L shl idx)
        return Board(next)
    }

    fun withCell(r: Int, c: Int): Board = Board(occupancy or (1L shl (r * SIZE + c)))

    fun cleared(): Board = Board(0L)

    /** Renders the board as an 8-line ASCII grid, useful in logs and debug overlays. */
    override fun toString(): String = buildString {
        for (r in 0 until SIZE) {
            for (c in 0 until SIZE) append(if (isFilled(r, c)) '#' else '.')
            if (r < SIZE - 1) append('\n')
        }
    }

    companion object {
        const val SIZE = 8
        const val CELL_COUNT = SIZE * SIZE

        val EMPTY = Board(0L)

        fun fromMask(occupancy: Long) = Board(occupancy and FULL_MASK)

        fun of(vararg filled: Pair<Int, Int>): Board {
            var mask = 0L
            for ((r, c) in filled) mask = mask or (1L shl (r * SIZE + c))
            return Board(mask)
        }

        const val FULL_MASK: Long = -1L ushr (64 - CELL_COUNT)
    }
}
