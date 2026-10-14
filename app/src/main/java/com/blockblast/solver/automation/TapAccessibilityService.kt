package com.blockblast.solver.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import com.blockblast.solver.core.SolverRuntime

/**
 * Performs the actual on-screen drag that places a piece.
 *
 * Block Blast is a drag-and-drop game: you press a piece in the tray, drag it over the board and
 * release. Without root the only sanctioned way to synthesise that input is an accessibility gesture,
 * so this service exposes a single [drag] primitive built on `dispatchGesture`.
 *
 * The service holds no game logic — it just executes a start point and an end point handed to it by
 * the runtime, which knows where the piece is and where it should land.
 */
class TapAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    /**
     * Drags from ([fromX], [fromY]) to ([toX], [toY]) over [durationMs], then releases.
     *
     * The path carries a couple of intermediate points so the game recognises a real drag rather than
     * a teleport, which some boards reject.
     */
    fun drag(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMs: Long = 220L): Boolean {
        val path = Path().apply {
            moveTo(fromX, fromY)
            lineTo((fromX + toX) / 2f, (fromY + toY) / 2f)
            lineTo(toX, toY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                SolverRuntime.onMovePerformed()
            }
        }, handler)
    }

    /** Taps a single point — used for menus and as a fallback when a drag is rejected. */
    fun tap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 40L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, handler)
    }

    companion object {
        /** The connected instance, or null when the user has not enabled the service. */
        @Volatile
        var instance: TapAccessibilityService? = null
            private set

        fun isConnected(): Boolean = instance != null
    }
}
