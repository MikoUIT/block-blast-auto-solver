package com.blockblast.solver.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.blockblast.solver.core.SolverRuntime

/**
 * Draws the debug / hint layer on top of the game.
 *
 * Two jobs share one canvas:
 *  - **Debug**: outline the detected board, mark occupied cells, box each recognised piece and show
 *    the recognition stats — this is the layer you stare at while tuning thresholds across themes.
 *  - **Hint / Solver**: highlight where the current suggestion wants the piece to go.
 *
 * The view is intentionally dumb: it is handed a [SolverRuntime.Snapshot] and draws it. All decision
 * making lives in the runtime.
 */
class OverlayView(context: Context) : View(context) {

    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.parseColor("#7C5CFF")
    }
    private val filledPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#557C5CFF")
    }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = Color.parseColor("#33FFFFFF")
    }
    private val pieceBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.parseColor("#5CC8FF")
    }
    private val suggestionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#88FFD45C")
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 34f
        setShadowLayer(4f, 0f, 2f, Color.BLACK)
    }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#CC1B1B1F")
    }

    @Volatile
    private var snapshot: SolverRuntime.Snapshot? = null

    var showDebug: Boolean = true
    var showHint: Boolean = true

    fun update(snapshot: SolverRuntime.Snapshot?) {
        this.snapshot = snapshot
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val snap = snapshot ?: return
        val grid = snap.grid ?: return

        if (showDebug) {
            // Board outline.
            canvas.drawRect(
                grid.left.toFloat(), grid.top.toFloat(),
                grid.right.toFloat(), grid.bottom.toFloat(), outlinePaint,
            )
            // Per-cell overlay.
            for (r in 0 until 8) {
                for (c in 0 until 8) {
                    val b = grid.cellBounds(r, c)
                    val rect = RectF(b[0].toFloat(), b[1].toFloat(), b[2].toFloat(), b[3].toFloat())
                    if (snap.board.isFilled(r, c)) {
                        canvas.drawRect(rect, filledPaint)
                    } else {
                        canvas.drawRect(rect, emptyPaint)
                    }
                }
            }
            // Piece boxes.
            for (p in snap.detectedPieces) {
                canvas.drawRect(
                    p.left.toFloat(), p.top.toFloat(),
                    p.right.toFloat(), p.bottom.toFloat(),
                    pieceBoxPaint,
                )
            }
        }

        if (showHint) {
            val suggestion = snap.suggestion
            if (suggestion != null) {
                for (idx in suggestion.placement.cells) {
                    val r = idx / 8
                    val c = idx % 8
                    val b = grid.cellBounds(r, c)
                    canvas.drawRect(
                        b[0].toFloat(), b[1].toFloat(), b[2].toFloat(), b[3].toFloat(),
                        suggestionPaint,
                    )
                }
            }
        }

        if (showDebug) {
            drawDiagnostics(canvas, snap)
        }
    }

    private fun drawDiagnostics(canvas: Canvas, snap: SolverRuntime.Snapshot) {
        val lines = buildList {
            add("кадр ${snap.frameId}  ${snap.solveMillis} ms")
            add("фигур найдено: ${snap.recognizedPieces.size} (совпало ${snap.piecesMatched})")
            add("клеток занято: ${snap.board.filledCount()}")
            snap.suggestion?.let {
                add("ход: чистит ${it.linesCleared}, счёт ~${"%.0f".format(it.projectedScore)}")
                it.rationale.take(2).forEach { line -> add("• $line") }
            }
        }
        val left = 24f
        var top = 24f
        val padding = 16f
        val lineHeight = textPaint.textSize + 12f
        val boxHeight = lines.size * lineHeight + padding * 2
        canvas.drawRoundRect(
            left - padding, top - padding + textPaint.textSize,
            left + 720f, top + boxHeight, 12f, 12f, badgePaint,
        )
        for (line in lines) {
            canvas.drawText(line, left, top + textPaint.textSize, textPaint)
            top += lineHeight
        }
    }
}
