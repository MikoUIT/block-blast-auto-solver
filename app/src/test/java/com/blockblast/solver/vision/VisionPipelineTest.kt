package com.blockblast.solver.vision

import com.blockblast.solver.model.Board
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionPipelineTest {

    private val layout = SyntheticFrame.Layout(left = 60, top = 120, size = 560)

    private fun boardOf(vararg cells: Pair<Int, Int>): BooleanArray {
        val arr = BooleanArray(64)
        for ((r, c) in cells) arr[r * 8 + c] = true
        return arr
    }

    @Test
    fun `detects the board rectangle on a dark frame`() {
        val frame = SyntheticFrame.render(
            width = 720, height = 1280, layout = layout,
            board = boardOf(), pieces = emptyList(),
        )
        val geom = GridDetector().detect(frame)
        assertNotNull("board should be detected", geom)
        // The detector should land close to the drawn rectangle.
        assertTrue("left ~60", kotlin.math.abs(geom!!.left - layout.left) < 20)
        assertTrue("top ~120", kotlin.math.abs(geom.top - layout.top) < 20)
        assertTrue("size ~560", kotlin.math.abs(geom.width - layout.size) < 30)
    }

    @Test
    fun `reads occupancy on a dark frame`() {
        val filled = boardOf(0 to 0, 2 to 3, 7 to 7, 4 to 4)
        val frame = SyntheticFrame.render(720, 1280, layout, filled, emptyList())
        val geom = GridDetector().detect(frame)!!
        val board = BoardRecognizer.occupancyOnly(frame, geom)
        for (r in 0 until 8) {
            for (c in 0 until 8) {
                assertEquals(
                    "cell ($r,$c)",
                    filled[r * 8 + c],
                    board.isFilled(r, c),
                )
            }
        }
    }

    @Test
    fun `reads occupancy on a light frame`() {
        val filled = boardOf(1 to 1, 5 to 2, 6 to 6)
        val frame = SyntheticFrame.render(
            720, 1280, layout, filled, emptyList(),
            palette = SyntheticFrame.Palette.LIGHT,
        )
        val geom = GridDetector().detect(frame)!!
        val board = BoardRecognizer.occupancyOnly(frame, geom)
        for (r in 0 until 8) {
            for (c in 0 until 8) {
                assertEquals("cell ($r,$c)", filled[r * 8 + c], board.isFilled(r, c))
            }
        }
    }

    @Test
    fun `recognises a domino piece in the tray`() {
        val frame = SyntheticFrame.render(
            720, 1280, layout,
            board = boardOf(),
            pieces = listOf(listOf(0 to 0, 0 to 1), emptyList(), emptyList()),
        )
        val trayTop = layout.top + layout.size + 30
        val pieces = PieceRecognizer().recognize(frame, trayTop, layout.cell.toDouble())
        assertTrue("expected at least one piece", pieces.isNotEmpty())
        val first = pieces.first()
        assertTrue("domino should match catalogue", first.matched)
        assertEquals(2, first.piece.cellCount)
    }

    @Test
    fun `recognises an L triomino in the tray`() {
        val frame = SyntheticFrame.render(
            720, 1280, layout,
            board = boardOf(),
            pieces = listOf(listOf(0 to 0, 1 to 0, 1 to 1), emptyList(), emptyList()),
        )
        val trayTop = layout.top + layout.size + 30
        val pieces = PieceRecognizer().recognize(frame, trayTop, layout.cell.toDouble())
        assertTrue("expected at least one piece", pieces.isNotEmpty())
        assertEquals(3, pieces.first().piece.cellCount)
        assertTrue(pieces.first().matched)
    }
}
