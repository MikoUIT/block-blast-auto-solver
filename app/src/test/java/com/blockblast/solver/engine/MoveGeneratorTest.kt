package com.blockblast.solver.engine

import com.blockblast.solver.model.Board
import com.blockblast.solver.model.Piece
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MoveGeneratorTest {

    private val gen = MoveGenerator()

    @Test
    fun `single cell on empty board has 64 placements`() {
        val dot = Piece.fromAscii("d", "#")
        assertEquals(64, gen.placements(Board.EMPTY, dot).size)
    }

    @Test
    fun `horizontal domino on empty board has 56 placements`() {
        val domino = Piece.fromAscii("d", "##")
        // 8 rows * 7 horizontal slots = 56.
        assertEquals(56, gen.placements(Board.EMPTY, domino).size)
    }

    @Test
    fun `vertical domino on empty board has 56 placements`() {
        val domino = Piece.fromAscii("d", "#\n#")
        // 7 row-pairs * 8 columns = 56.
        assertEquals(56, gen.placements(Board.EMPTY, domino).size)
    }

    @Test
    fun `placement is rejected when it collides`() {
        val board = Board.of(0 to 0)
        val dot = Piece.fromAscii("d", "#")
        assertNull(gen.tryPlace(board, dot, 0, 0))
        assertNotNull(gen.tryPlace(board, dot, 0, 1))
    }

    @Test
    fun `placement is rejected when it leaves the board`() {
        val dot = Piece.fromAscii("d", "#")
        assertNull(gen.tryPlace(Board.EMPTY, dot, 8, 0))
        assertNull(gen.tryPlace(Board.EMPTY, dot, 0, 8))
    }

    @Test
    fun `filling a row clears it`() {
        // Row 0 has seven filled cells; placing the last one on the right completes it.
        val board = Board.of(*Array(7) { 0 to it })
        val dot = Piece.fromAscii("d", "#")
        val placement = gen.tryPlace(board, dot, 0, 7)!!
        assertEquals(1, placement.clears)
        val after = gen.apply(board, placement)
        assertTrue("row should be empty after clear", (0 until 8).none { after.isFilled(0, it) })
    }

    @Test
    fun `filling a column clears it`() {
        val board = Board.of(*Array(7) { it to 0 })
        val dot = Piece.fromAscii("d", "#")
        val placement = gen.tryPlace(board, dot, 7, 0)!!
        assertEquals(1, placement.clears)
        val after = gen.apply(board, placement)
        assertTrue("column should be empty after clear", (0 until 8).none { after.isFilled(it, 0) })
    }

    @Test
    fun `crossing placement can clear a row and a column at once`() {
        val row = Array(7) { 0 to it + 1 }
        val col = Array(7) { it + 1 to 0 }
        val board = Board.of(*row, *col)
        val dot = Piece.fromAscii("d", "#")
        val placement = gen.tryPlace(board, dot, 0, 0)!!
        assertEquals(2, placement.clears)
        val after = gen.apply(board, placement)
        assertTrue((0 until 8).none { after.isFilled(0, it) })
        assertTrue((0 until 8).none { after.isFilled(it, 0) })
    }

    @Test
    fun `apply without clear preserves placed cells`() {
        val dot = Piece.fromAscii("d", "#")
        val placement = gen.tryPlace(Board.EMPTY, dot, 3, 4)!!
        val after = gen.apply(Board.EMPTY, placement)
        assertTrue(after.isFilled(3, 4))
        assertEquals(1, after.filledCount())
    }
}
