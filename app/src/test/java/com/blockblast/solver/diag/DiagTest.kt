package com.blockblast.solver.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagTest {

    @Test
    fun `ring buffer keeps lines oldest first`() {
        Diag.clear()
        Diag.i("Test", "first")
        Diag.i("Test", "second")
        val lines = Diag.snapshot()
        assertEquals(2, lines.size)
        assertTrue(lines[0].endsWith("I/Test: first"))
        assertTrue(lines[1].endsWith("I/Test: second"))
    }

    @Test
    fun `ring buffer is bounded`() {
        Diag.clear()
        repeat(2000) { Diag.d("Test", "line $it") }
        // MAX_LINES is 1500; the buffer must not grow past it.
        assertTrue(Diag.snapshot().size <= 1500)
    }

    @Test
    fun `errors include the stack trace`() {
        Diag.clear()
        Diag.e("Test", "boom", IllegalStateException("bad state"))
        val joined = Diag.snapshot().joinToString("\n")
        assertTrue(joined.contains("boom"))
        assertTrue(joined.contains("IllegalStateException"))
    }
}
