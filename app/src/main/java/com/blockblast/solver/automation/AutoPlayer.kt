package com.blockblast.solver.automation

import android.os.Handler
import android.os.Looper
import com.blockblast.solver.core.SolverRuntime

/**
 * Turns a suggestion into an actual drag, but only in [SolverRuntime.Mode.SOLVER].
 *
 * It listens for snapshots, and once it sees a suggestion backed by a recognised piece it drags that
 * piece onto the suggested cells. A short cooldown after each move prevents the runtime from firing
 * again on the same, not-yet-updated board — without it the solver would place the same piece twice
 * before the game had a chance to redraw.
 */
class AutoPlayer(
    private val cooldownMs: Long = 600L,
) : SolverRuntime.Listener {

    private val handler = Handler(Looper.getMainLooper())
    private var lastMoveAt = 0L
    private var pending = false

    fun attach() {
        SolverRuntime.addListener(this)
    }

    fun detach() {
        SolverRuntime.removeListener(this)
        handler.removeCallbacksAndMessages(null)
        pending = false
    }

    override fun onSnapshot(snapshot: SolverRuntime.Snapshot) {
        if (SolverRuntime.mode != SolverRuntime.Mode.SOLVER) return
        if (pending) return
        val suggestion = snapshot.suggestion ?: return
        val grid = snapshot.grid ?: return

        val now = System.currentTimeMillis()
        if (now - lastMoveAt < cooldownMs) return

        // The piece being placed is the one whose shape matches the suggested placement.
        val pieceBox = snapshot.detectedPieces.firstOrNull {
            it.piece.mask == suggestion.placement.piece.mask &&
                it.piece.rows == suggestion.placement.piece.rows &&
                it.piece.cols == suggestion.placement.piece.cols
        } ?: return

        val service = TapAccessibilityService.instance ?: return
        val fromX = (pieceBox.left + pieceBox.right) / 2f
        val fromY = (pieceBox.top + pieceBox.bottom) / 2f
        val (targetX, targetY) = grid.cellCenter(
            suggestion.placement.anchorRow,
            suggestion.placement.anchorCol,
        )

        pending = true
        handler.post {
            val ok = service.drag(fromX, fromY, targetX.toFloat(), targetY.toFloat())
            lastMoveAt = System.currentTimeMillis()
            // Release the guard after roughly the drag duration plus the redraw settle time.
            handler.postDelayed({ pending = false }, 450L)
            if (!ok) pending = false
        }
    }

    override fun onCaptureStopped() {
        pending = false
    }

    override fun onCaptureError(message: String) = Unit
}
