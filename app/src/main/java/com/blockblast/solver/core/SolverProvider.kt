package com.blockblast.solver.core

import com.blockblast.solver.engine.HeuristicConfig
import com.blockblast.solver.engine.ScoringConfig
import com.blockblast.solver.engine.Solver

/**
 * Single source of truth for the solver instance so the capture thread and the overlay do not each
 * build (and diverge from) their own. Tuning values read from settings can be pushed here once the
 * UI wires them up.
 */
object SolverProvider {
    @Volatile
    var scoring: ScoringConfig = ScoringConfig.DEFAULT
        private set
    @Volatile
    var heuristics: HeuristicConfig = HeuristicConfig.DEFAULT
        private set

    @Volatile
    var solver: Solver = Solver(scoring, heuristics)
        private set

    fun update(scoring: ScoringConfig = this.scoring, heuristics: HeuristicConfig = this.heuristics) {
        this.scoring = scoring
        this.heuristics = heuristics
        solver = Solver(scoring, heuristics)
    }
}
