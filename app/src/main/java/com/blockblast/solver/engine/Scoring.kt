package com.blockblast.solver.engine

import com.blockblast.solver.model.Placement

/**
 * Tunable scoring model for Block Blast.
 *
 * The exact point values the game uses are not published, so every number lives here behind a
 * named field and can be adjusted once we read them off a live run. The engine never hard-codes a
 * score — it always asks this object. Keeping the model in one place also makes it trivial to A/B a
 * safer vs. greedier weighting without touching the search.
 */
data class ScoringConfig(
    /** Points awarded per block cell placed. */
    val pointsPerCell: Double = 1.0,
    /** Base points awarded for clearing a single line (row or column). */
    val pointsPerLineClear: Double = 8.0,
    /**
     * Extra points for the *k*-th additional line cleared in the same move, so that clearing two
     * lines at once is worth meaningfully more than two separate clears. Indexed by (extraLines - 1).
     */
    val multiClearBonus: List<Double> = listOf(6.0, 14.0, 24.0, 36.0, 50.0, 66.0, 84.0),
    /** Multiplier applied on top of a clear when the previous move also cleared something (combo). */
    val comboStep: Double = 0.5,
    /** Cap on the combo multiplier so a lucky run does not explode the evaluation. */
    val comboMax: Double = 4.0,
) {
    /** Score for clearing [lines] lines at once, before the combo multiplier. */
    fun clearScore(lines: Int, comboMultiplier: Double): Double {
        if (lines <= 0) return 0.0
        var total = pointsPerLineClear * lines
        for (i in 1 until lines) {
            total += multiClearBonus.getOrElse(i - 1) { multiClearBonus.lastOrNull() ?: 0.0 }
        }
        return total * comboMultiplier
    }

    /** Combo multiplier for a run of [comboCount] consecutive scoring moves (0 == none). */
    fun comboMultiplier(comboCount: Int): Double =
        (1.0 + comboStep * comboCount).coerceAtMost(comboMax)

    companion object {
        val DEFAULT = ScoringConfig()
    }
}

/** The immediate reward of one placement, plus bookkeeping the search needs. */
data class MoveScore(
    val placement: Placement,
    val immediatePoints: Double,
    val linesCleared: Int,
    val resultingCombo: Int,
    val boardAfter: com.blockblast.solver.model.Board,
)
