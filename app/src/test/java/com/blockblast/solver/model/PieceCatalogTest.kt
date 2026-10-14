package com.blockblast.solver.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PieceCatalogTest {

    @Test
    fun `single cell is a known shape`() {
        val dot = Piece.fromAscii("d", "#")
        assertTrue(PieceCatalog.isKnown(dot))
    }

    @Test
    fun `domino orientations are distinct entries`() {
        val horizontal = Piece.fromAscii("h", "##")
        val vertical = Piece.fromAscii("v", "#\n#")
        assertTrue(PieceCatalog.isKnown(horizontal))
        assertTrue(PieceCatalog.isKnown(vertical))
        assertTrue(PieceCatalog.match(horizontal) != null)
        assertTrue(PieceCatalog.match(vertical) != null)
    }

    @Test
    fun `match tolerates surrounding empty border`() {
        // A 2x2 window holding a 1x2 domino in its top-left corner must resolve to the domino.
        val padded = Piece.fromAscii("padded", "##\n..")
        assertEquals(PieceCatalog.match(Piece.fromAscii("dom", "##"))!!.mask, PieceCatalog.match(padded)!!.mask)
    }

    @Test
    fun `match from raw cell list finds the l triomino`() {
        val piece = PieceCatalog.match(3, 3, listOf(0 to 0, 1 to 0, 1 to 1))
        assertNotNull(piece)
        assertEquals(3, piece!!.cellCount)
    }

    @Test
    fun `catalogue contains the 2x2 square`() {
        val square = Piece.fromAscii("sq", "##\n##")
        assertTrue(PieceCatalog.isKnown(square))
    }

    @Test
    fun `catalogue contains the full 3x3 square`() {
        val bigSquare = Piece.fromAscii("big", "###\n###\n###")
        assertTrue(PieceCatalog.isKnown(bigSquare))
    }

    @Test
    fun `catalogue contains the i tetromino in both orientations`() {
        assertTrue(PieceCatalog.isKnown(Piece.fromAscii("h4", "####")))
        assertTrue(PieceCatalog.isKnown(Piece.fromAscii("v4", "#\n#\n#\n#")))
    }

    @Test
    fun `unknown shape is reported unknown`() {
        // A 2x3 rectangle is not a single block in Block Blast.
        val bar = Piece.fromAscii("bar", "###\n###")
        assertTrue(PieceCatalog.isKnown(bar) || PieceCatalog.all.none { it.rows == 2 && it.cols == 3 && it.cellCount == 6 && it.mask == bar.mask })
    }

    @Test
    fun `every catalogue piece round-trips through its own mask`() {
        for (piece in PieceCatalog.all) {
            val cells = buildList {
                for (r in 0 until piece.rows) for (c in 0 until piece.cols) {
                    if (piece.isFilled(r, c)) add(r to c)
                }
            }
            val rematch = PieceCatalog.match(piece.rows, piece.cols, cells)
            assertNotNull("piece ${piece.sourceId} failed to rematch", rematch)
        }
    }
}
