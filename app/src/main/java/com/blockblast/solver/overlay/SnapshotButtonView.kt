package com.blockblast.solver.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/**
 * A small floating camera button that stays clickable above the game.
 *
 * Unlike the diagnostic overlay (which is deliberately non-touchable), this one must receive taps,
 * so it lives in its own overlay window. Tapping it captures a burst of frames for a bug report,
 * which is the fastest way to collect real screenshots of whatever theme the game is wearing.
 */
class SnapshotButtonView(context: Context, private val onCapture: () -> Unit) : View(context) {

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#CC1B1B1F")
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.parseColor("#7C5CFF")
    }
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 20f
        textAlign = Paint.Align.CENTER
    }

    private val bounds = RectF()
    private var pressed = false

    init {
        isClickable = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        bounds.set(2f, 2f, w - 2f, h - 2f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val r = bounds.height() / 2f
        canvas.drawRoundRect(bounds, r, r, if (pressed) ring else bg)
        if (!pressed) canvas.drawRoundRect(bounds, r, r, ring)
        val cx = bounds.centerX()
        val cy = bounds.centerY() - (icon.descent() + icon.ascent()) / 2f
        canvas.drawText("📷", cx, cy, icon)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                pressed = true
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                pressed = false
                invalidate()
                onCapture()
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                pressed = false
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
