package com.blockblast.solver.engine

import com.blockblast.solver.model.Board

/**
 * Heuristic "board health" evaluation used to compare boards that are not distinguished by
 * immediate points. Because Block Blast is a survival game first and a scoring game second, the
 * engine needs a positional signal that rewards open, connected space and punishes the cramped,
 * fragmented boards that lead to a dead end.
 *
 * All terms are intentionally cheap — this runs at the leaves of the search.
 */
object BoardEvaluator {

    /**
     * Returns a positional score in roughly the same units as points, so it can be blended with the
     * immediate reward. Higher is healthier.
     */
    fun evaluate(board: Board, config: HeuristicConfig = HeuristicConfig.DEFAULT): Double {
        val empty = Board.CELL_COUNT - board.filledCount()
        if (empty == 0) return -config.deadEndPenalty

        val components = connectedComponents(board)
        val isolated = lonelyCells(board)
        val edgeAdjacency = adjacencySmoothness(board)

        var score = 0.0
        score += config.emptyWeight * empty
        score -= config.fragmentationPenalty * (components - 1)
        score -= config.isolatedPenalty * isolated
        score += config.smoothnessWeight * edgeAdjacency
        return score
    }

    /** Number of 4-connected empty regions. A single region is ideal; many means fragmentation. */
    fun connectedComponents(board: Board): Int {
        val seen = BooleanArray(Board.CELL_COUNT)
        var components = 0
        for (i in 0 until Board.CELL_COUNT) {
            if (isFilled(board, i) || seen[i]) continue
            components++
            floodEmpty(board, i, seen)
        }
        return components
    }

    /** Counts empty cells that have no empty orthogonal neighbour — the worst kind of gap. */
    fun lonelyCells(board: Board): Int {
        var count = 0
        for (r in 0 until Board.SIZE) {
            for (c in 0 until Board.SIZE) {
                if (board.isFilled(r, c)) continue
                val upEmpty = r - 1 >= 0 && !board.isFilled(r - 1, c)
                val downEmpty = r + 1 < Board.SIZE && !board.isFilled(r + 1, c)
                val leftEmpty = c - 1 >= 0 && !board.isFilled(r, c - 1)
                val rightEmpty = c + 1 < Board.SIZE && !board.isFilled(r, c + 1)
                if (!upEmpty && !downEmpty && !leftEmpty && !rightEmpty) count++
            }
        }
        return count
    }

    /** Counts transitions between filled and empty cells across the whole board (both axes). */
    fun adjacencySmoothness(board: Board): Int {
        var transitions = 0
        for (r in 0 until Board.SIZE) {
            for (c in 0 until Board.SIZE) {
                if (c + 1 < Board.SIZE && board.isFilled(r, c) != board.isFilled(r, c + 1)) transitions++
                if (r + 1 < Board.SIZE && board.isFilled(r, c) != board.isFilled(r + 1, c)) transitions++
            }
        }
        return transitions
    }

    private fun isFilled(board: Board, index: Int): Boolean =
        board.isFilled(index / Board.SIZE, index % Board.SIZE)

    private fun floodEmpty(board: Board, start: Int, seen: BooleanArray) {
        val stack = ArrayDeque<Int>(Board.CELL_COUNT)
        stack.addLast(start)
        seen[start] = true
        while (stack.isNotEmpty()) {
            val idx = stack.removeLast()
            val r = idx / Board.SIZE
            val c = idx % Board.SIZE
            for ((dr, dc) in NEIGHBOURS) {
                val nr = r + dr
                val nc = c + dc
                if (nr < 0 || nc < 0 || nr >= Board.SIZE || nc >= Board.SIZE) continue
                val nIdx = nr * Board.SIZE + nc
                if (seen[nIdx] || isFilled(board, nIdx)) continue
                seen[nIdx] = true
                stack.addLast(nIdx)
            }
        }
    }

    private val NEIGHBOURS = arrayOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
}

/** Weights for [BoardEvaluator]. Tuned by play-testing; kept separate so it can be swept. */
data class HeuristicConfig(
    val emptyWeight: Double = 2.0,
    val fragmentationPenalty: Double = 6.0,
    val isolatedPenalty: Double = 4.0,
    val smoothnessWeight: Double = 0.15,
    val deadEndPenalty: Double = 1000.0,
) {
    companion object {
        val DEFAULT = HeuristicConfig()
    }
}
