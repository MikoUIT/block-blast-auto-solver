package com.blockblast.solver.overlay

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import com.blockblast.solver.core.SolverRuntime

/**
 * Hosts [OverlayView] in a system overlay window that floats above the game.
 *
 * The window is created with `TYPE_APPLICATION_OVERLAY` (the only overlay type allowed on modern
 * Android without root) and is flagged non-touchable, so it never steals a tap meant for the game —
 * which matters both for honest manual play and because the accessibility gesture dispatcher would
 * otherwise fight the overlay for the same touch stream.
 *
 * The service is also the overlay's data link: it registers a [SolverRuntime.Listener] and pushes
 * each snapshot straight into the view on the main thread.
 */
class OverlayService : Service(), SolverRuntime.Listener {

    private var windowManager: WindowManager? = null
    private var view: OverlayView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        addOverlay()
        SolverRuntime.addListener(this)
    }

    private fun addOverlay() {
        if (view != null) return
        val overlayView = OverlayView(this)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        windowManager?.addView(overlayView, params)
        view = overlayView
        layoutParams = params
    }

    private fun removeOverlay() {
        view?.let { windowManager?.removeView(it) }
        view = null
        layoutParams = null
    }

    override fun onSnapshot(snapshot: SolverRuntime.Snapshot) {
        view?.post { view?.update(snapshot) }
    }

    override fun onCaptureStopped() {
        view?.post { view?.update(null) }
    }

    override fun onCaptureError(message: String) {
        // Keep the last good frame on screen; a transient miss should not blank the overlay.
    }

    override fun onDestroy() {
        SolverRuntime.removeListener(this)
        removeOverlay()
        super.onDestroy()
    }

    companion object {
        const val ACTION_SET_DEBUG = "com.blockblast.solver.overlay.DEBUG"
        const val ACTION_SET_HINT = "com.blockblast.solver.overlay.HINT"
        const val EXTRA_ENABLED = "enabled"

        fun startIntent(context: Context): Intent =
            Intent(context, OverlayService::class.java)

        fun stopIntent(context: Context): Intent =
            Intent(context, OverlayService::class.java).apply { action = "STOP" }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "STOP" -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SET_DEBUG -> view?.showDebug = intent.getBooleanExtra(EXTRA_ENABLED, true)
            ACTION_SET_HINT -> view?.showHint = intent.getBooleanExtra(EXTRA_ENABLED, true)
        }
        return START_STICKY
    }
}
