package com.blockblast.solver.engine

import com.blockblast.solver.model.Board
import com.blockblast.solver.model.Piece
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SolverTest {

    private val solver = Solver()

    @Test
    fun `solver prefers the move that clears a line`() {
        // Row 0 needs one more cell; a single-cell piece can finish it.
        val board = Board.of(*Array(7) { 0 to it })
        val dot = Piece.fromAscii("d", "#")
        val filler = Piece.fromAscii("f", "##\n##")
        val suggestion = solver.suggest(board, listOf(dot, filler))
        assertNotNull(suggestion)
        assertEquals("should finish the row", 1, suggestion!!.linesCleared)
        assertTrue(suggestion.placement.anchorRow == 0 && suggestion.placement.anchorCol == 7)
    }

    @Test
    fun `solver returns something for an empty board`() {
        val pieces = listOf(
            Piece.fromAscii("a", "##"),
            Piece.fromAscii("b", "#\n#"),
            Piece.fromAscii("c", "###"),
        )
        val suggestion = solver.suggest(Board.EMPTY, pieces)
        assertNotNull(suggestion)
        assertTrue(suggestion!!.projectedScore > 0)
    }

    @Test
    fun `solver returns null when nothing fits`() {
        // Board nearly full: only a single isolated cell is empty.
        val full = Board.FULL_MASK and (1L shl 0).inv()
        val board = Board.fromMask(full)
        val domino = Piece.fromAscii("d", "##")
        // A single domino cannot fit anywhere when only one cell is free.
        assertEquals(null, solver.suggest(board, listOf(domino)))
    }

    @Test
    fun `combo multiplier grows and then resets`() {
        val config = ScoringConfig.DEFAULT
        assertEquals(1.0, config.comboMultiplier(0), 1e-9)
        assertEquals(1.5, config.comboMultiplier(1), 1e-9)
        assertEquals(2.0, config.comboMultiplier(2), 1e-9)
        // A gap resets the combo, represented by passing 0.
        assertEquals(1.0, config.comboMultiplier(0), 1e-9)
    }

    @Test
    fun `clear score rewards simultaneous clears more than separate ones`() {
        val config = ScoringConfig.DEFAULT
        val oneAtATime = config.clearScore(1, 1.0) * 2
        val together = config.clearScore(2, 1.0)
        assertTrue("two at once should beat two separate clears", together > oneAtATime)
    }
}
