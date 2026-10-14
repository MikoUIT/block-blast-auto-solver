package com.blockblast.solver.core

import android.media.Image
import com.blockblast.solver.diag.Diag
import com.blockblast.solver.model.Board
import com.blockblast.solver.model.Piece
import com.blockblast.solver.model.Placement
import com.blockblast.solver.vision.BoardRecognizer
import com.blockblast.solver.vision.FrameConverter
import com.blockblast.solver.vision.GridDetector
import com.blockblast.solver.vision.PieceRecognizer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The single coordination point between capture, vision, the solver, the overlay and the UI.
 *
 * Frames arrive on the capture handler thread; the runtime throttles them to a target rate, runs the
 * recognition + solve pipeline, and publishes a [Snapshot] to whoever is listening (the overlay and
 * the UI). It deliberately holds no Android view references — only plain data — so the recognition
 * path stays testable and free of leaks.
 */
object SolverRuntime {

    /** Immutable view of the last processed frame, consumed by the overlay and the UI. */
    data class Snapshot(
        val board: Board,
        val recognizedPieces: List<Piece>,
        val piecesMatched: Int,
        val grid: GridDetector.GridGeometry?,
        val suggestion: Suggestion?,
        val detectedPieces: List<PieceRecognizer.RecognizedPiece>,
        val frameId: Long,
        val solveMillis: Long,
        /** The last processed luminance frame, so a snapshot can be rendered with its overlay. */
        val gray: com.blockblast.solver.vision.GrayImage?,
    )

    data class Suggestion(
        val placement: Placement,
        val linesCleared: Int,
        val projectedScore: Double,
        val rationale: List<String>,
    )

    /** How the runtime behaves once a suggestion exists. */
    enum class Mode { HINT, SOLVER }

    interface Listener {
        fun onSnapshot(snapshot: Snapshot)
        fun onCaptureStopped()
        fun onCaptureError(message: String)
    }

    private val running = AtomicBoolean(false)
    private var frameId = 0L

    @Volatile
    private var lastFrameTime = 0L

    @Volatile
    var mode: Mode = Mode.HINT

    @Volatile
    var targetFps: Int = 15

    /**
     * Listeners are kept in a list rather than a single slot: the overlay and the auto-player both
     * need every snapshot and used to clobber each other's registration.
     */
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<Listener>()

    private val gridDetector = GridDetector()
    private val boardRecognizer = BoardRecognizer()
    private val pieceRecognizer = PieceRecognizer()
    private val engine = SolverProvider.solver

    /** Geometry from the last successful detection, reused when a frame fails to detect. */
    @Volatile
    private var cachedGeometry: GridDetector.GridGeometry? = null

    /** Total frames handed to us this session; used for the early "frames flow" diagnostics. */
    @Volatile
    private var receivedFrames = 0

    /** Most recent snapshot, so the camera button can capture the current frame on demand. */
    @Volatile
    var lastSnapshot: Snapshot? = null
        private set

    fun addListener(l: Listener) {
        if (!listeners.contains(l)) listeners.add(l)
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    fun start() {
        running.set(true)
        frameId = 0
    }

    fun isRunning(): Boolean = running.get()

    /**
     * Entry point from the capture service. Called on the capture handler thread, so it must stay
     * fast — heavy work is bounded by the frame throttle and the solver's beam cap.
     */
    fun onFrame(image: Image, width: Int, height: Int) {
        if (!running.get()) return
        // Log the first few arrivals so the report shows whether frames flow at all — the most
        // common failure is a silent no-frame stream, which used to leave no trace.
        receivedFrames++
        if (receivedFrames <= 3) {
            Diag.i(TAG, "frame #$receivedFrames arrived ${image.width}x${image.height}")
        }
        val frameInterval = (1000L / targetFps.coerceAtLeast(1))
        val now = System.currentTimeMillis()
        if (now - lastFrameTime < frameInterval) return
        lastFrameTime = now

        val started = System.currentTimeMillis()
        val gray = FrameConverter.toGray(image)
        val grid = gridDetector.detect(gray) ?: cachedGeometry
        if (grid == null) {
            Diag.w(TAG, "board not found in frame")
            for (l in listeners) l.onCaptureError("Поле не найдено")
            return
        }
        cachedGeometry = grid

        val recognized = boardRecognizer.recognize(gray, grid)
        val trayTop = grid.bottom + (grid.height * 0.06).toInt()
        val detected = pieceRecognizer.recognize(gray, trayTop, grid.cellWidth)
        val pieces = detected.map { it.piece }

        val suggestion = if (pieces.isNotEmpty()) {
            engine.suggest(recognized.board, pieces)
        } else null

        val newFrameId = ++frameId
        // Log a compact summary every ~30 frames so the report shows real recognition state
        // without drowning in 30-fps noise.
        if (newFrameId % 30 == 1L) {
            Diag.i(
                TAG,
                "frame=$newFrameId grid=${grid.left},${grid.top},${grid.width}x${grid.height} " +
                    "filled=${recognized.board.filledCount()} pieces=${pieces.size}" +
                    "(matched=${detected.count { it.matched }}) " +
                    "suggest=${suggestion?.let { "clears=${it.linesCleared} score=${"%.0f".format(it.projectedScore)}" } ?: "none"} " +
                    "ms=${System.currentTimeMillis() - started}",
            )
        }

        val snapshot = Snapshot(
            board = recognized.board,
            recognizedPieces = pieces,
            piecesMatched = detected.count { it.matched },
            grid = grid,
            suggestion = suggestion?.let {
                Suggestion(it.placement, it.linesCleared, it.projectedScore, it.rationale)
            },
            detectedPieces = detected,
            frameId = newFrameId,
            solveMillis = System.currentTimeMillis() - started,
            gray = gray,
        )
        for (l in listeners) l.onSnapshot(snapshot)
        lastSnapshot = snapshot
    }

    fun onCaptureStopped() {
        running.set(false)
        cachedGeometry = null
        for (l in listeners) l.onCaptureStopped()
    }

    /** Invoked by the automation layer once a move has actually been performed. */
    fun onMovePerformed() {
        lastFrameTime = 0L
    }

    private const val TAG = "SolverRuntime"
}
