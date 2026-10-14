package com.blockblast.solver.diag

import com.blockblast.solver.core.SolverRuntime
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Holds the snapshots the user captured with the floating camera button.
 *
 * The flow is: tap the camera a few times across different game states/boards, then press "send
 * report". Each tap stores the plain + annotated view of that frame here, and the report inlines
 * them all. The buffer is bounded so a long session cannot grow without limit.
 */
object SnapshotCollector {

    private const val MAX_FRAMES = 8
    private val frames = CopyOnWriteArrayList<SnapshotRenderer.Frame>()

    /** Captures the given snapshot; returns the new buffer size. */
    fun capture(snapshot: SolverRuntime.Snapshot): Int {
        val gray = snapshot.gray ?: return frames.size
        val frame = SnapshotRenderer.render(
            gray,
            snapshot,
            label = "frame ${snapshot.frameId}",
        )
        frames.add(frame)
        while (frames.size > MAX_FRAMES) frames.removeAt(0)
        Diag.i("Snapshot", "captured ${frames.size} frame(s), label=${frame.label}")
        return frames.size
    }

    fun all(): List<SnapshotRenderer.Frame> = frames.toList()

    fun count(): Int = frames.size

    fun clear() = frames.clear()
}
