package com.blockblast.solver.engine

import com.blockblast.solver.model.Board
import com.blockblast.solver.model.Piece
import com.blockblast.solver.model.Placement

/**
 * The decision core: given the current board and the three pieces on offer, choose the next move.
 *
 * Strategy
 * --------
 * The game hands out three pieces at once, so we can look ahead across the whole triple even though
 * we only *play* the first move. A beam search walks every ordering of the three pieces and, at each
 * depth, keeps only the most promising branches. A branch's value blends:
 *
 *  - immediate points earned by clears (with the combo multiplier),
 *  - the positional health of the resulting board (see [BoardEvaluator]),
 *  - a penalty for pieces that end up with nowhere to go.
 *
 * The search is deliberately bounded: a phone has to return a move in a frame or two, so we cap the
 * beam width and always keep at least the single best placement per piece.
 */
class Solver(
    private val scoring: ScoringConfig = ScoringConfig.DEFAULT,
    private val heuristics: HeuristicConfig = HeuristicConfig.DEFAULT,
    private val beamWidth: Int = 24,
) {
    private val generator = MoveGenerator()

    /** A scored candidate move: the placement to play plus the reason it was chosen. */
    data class Suggestion(
        val placement: Placement,
        val projectedScore: Double,
        val immediatePoints: Double,
        val linesCleared: Int,
        val boardAfter: Board,
        /** Human-readable one-liners explaining the decision, surfaced in the debug overlay. */
        val rationale: List<String>,
    )

    /**
     * Chooses the single best move for the *current* triple of [pieces].
     *
     * @param board        current occupancy
     * @param pieces       the (up to three) pieces currently offered; pass only the ones that exist
     * @param currentCombo how many consecutive scoring moves preceded this one (0 == none)
     */
    fun suggest(board: Board, pieces: List<Piece>, currentCombo: Int = 0): Suggestion? {
        if (pieces.isEmpty()) return null

        // Per-piece best placement, used to seed the search and as a fallback.
        val perPiece = pieces.map { piece ->
            val placements = generator.placements(board, piece)
            placements.maxByOrNull { quickValue(board, it, currentCombo) }
        }

        val seeds = mutableListOf<SearchNode>()
        for ((index, piece) in pieces.withIndex()) {
            val best = perPiece[index] ?: continue
            val applied = applyMove(board, best, currentCombo)
            seeds.add(
                SearchNode(
                    board = applied.boardAfter,
                    combo = applied.comboAfter,
                    accumulated = applied.points + BoardEvaluator.evaluate(applied.boardAfter, heuristics),
                    firstPlacement = best,
                    firstImmediatePoints = applied.points,
                    firstLines = best.clears,
                    remaining = pieces.filterIndexed { i, _ -> i != index },
                    depth = 1,
                )
            )
        }

        if (seeds.isEmpty()) return null

        var frontier = seeds.sortedByDescending { it.accumulated }.take(beamWidth)
        // Expand across the remaining pieces, depth-first in effect, breadth-capped at each level.
        for (depth in 1 until pieces.size) {
            val next = mutableListOf<SearchNode>()
            for (node in frontier) {
                if (node.remaining.isEmpty()) {
                    next.add(node)
                    continue
                }
                var anyPlaced = false
                for ((i, piece) in node.remaining.withIndex()) {
                    val placements = generator.placements(node.board, piece)
                    if (placements.isEmpty()) continue
                    anyPlaced = true
                    // Keep only the strongest placements of this piece to bound the fan-out.
                    val top = placements.sortedByDescending { quickValue(node.board, it, node.combo) }
                        .take(MAX_PLACEMENTS_PER_PIECE)
                    for (placement in top) {
                        val applied = applyMove(node.board, placement, node.combo)
                        next.add(
                            node.copy(
                                board = applied.boardAfter,
                                combo = applied.comboAfter,
                                accumulated = node.accumulated + applied.points +
                                    BoardEvaluator.evaluate(applied.boardAfter, heuristics),
                                remaining = node.remaining.filterIndexed { j, _ -> j != i },
                                depth = depth + 1,
                            )
                        )
                    }
                }
                // A node where nothing fits is a dead end; keep it so its penalty is visible.
                if (!anyPlaced) {
                    next.add(node.copy(accumulated = node.accumulated - heuristics.deadEndPenalty))
                }
            }
            frontier = next.sortedByDescending { it.accumulated }.take(beamWidth)
        }

        val best = frontier.maxByOrNull { it.accumulated } ?: return null
        val rationale = buildRationale(best, pieces.size)
        return Suggestion(
            placement = best.firstPlacement,
            projectedScore = best.accumulated,
            immediatePoints = best.firstImmediatePoints,
            linesCleared = best.firstLines,
            boardAfter = generator.apply(board, best.firstPlacement),
            rationale = rationale,
        )
    }

    private fun buildRationale(node: SearchNode, pieceCount: Int): List<String> = buildList {
        if (node.firstLines > 0) {
            add("Очищает ${node.firstLines} лини${lineWord(node.firstLines)} за ход")
        }
        val eval = BoardEvaluator.evaluate(node.board, heuristics)
        if (eval < 0) add("Поле стесняется: оценка здоровья ${"%.0f".format(eval)}")
        val components = BoardEvaluator.connectedComponents(node.board)
        if (components > 1) add("Свободное место распадается на $components областей")
        if (node.depth < pieceCount) add("Просчитано ${node.depth} из $pieceCount фигур")
        if (isEmpty()) add("Единственный безопасный вариант")
    }

    private fun lineWord(n: Int): String = when {
        n % 10 == 1 && n % 100 != 11 -> "ю"
        n % 10 in 2..4 && n % 100 !in 12..14 -> "и"
        else -> "й"
    }

    private fun quickValue(board: Board, placement: Placement, combo: Int): Double {
        val after = generator.apply(board, placement)
        val points = immediatePoints(placement, combo)
        return points + BoardEvaluator.evaluate(after, heuristics)
    }

    private fun immediatePoints(placement: Placement, combo: Int): Double {
        val multiplier = scoring.comboMultiplier(combo)
        return scoring.pointsPerCell * placement.cellCount + scoring.clearScore(placement.clears, multiplier)
    }

    private fun applyMove(board: Board, placement: Placement, combo: Int): Applied {
        val after = generator.apply(board, placement)
        val points = immediatePoints(placement, combo)
        val newCombo = if (placement.clears > 0) combo + 1 else 0
        return Applied(after, points, newCombo)
    }

    private data class Applied(val boardAfter: Board, val points: Double, val comboAfter: Int)

    private data class SearchNode(
        val board: Board,
        val combo: Int,
        val accumulated: Double,
        val firstPlacement: Placement,
        val firstImmediatePoints: Double,
        val firstLines: Int,
        val remaining: List<Piece>,
        val depth: Int,
    )

    private companion object {
        /** How many placements of a single piece survive into the next beam level. */
        const val MAX_PLACEMENTS_PER_PIECE = 32
    }
}
