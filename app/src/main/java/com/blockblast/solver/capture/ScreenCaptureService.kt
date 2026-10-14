package com.blockblast.solver.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import com.blockblast.solver.R
import com.blockblast.solver.core.SolverRuntime
import com.blockblast.solver.diag.Diag

/**
 * Owns the single `MediaProjection` capture session.
 *
 * A projection session must outlive any single Activity and must be started from the foreground, so
 * it lives in a foreground service. The service sets up a `VirtualDisplay` that mirrors the screen
 * into an `ImageReader`, and forwards each frame to [SolverRuntime] for recognition and solving.
 *
 * Android 14+ rules enforced here:
 *  - the service must declare and start with the `mediaProjection` foreground type,
 *  - `startForeground` must be called *before* the projection is used,
 *  - a projection token can only be turned into a `MediaProjection` once.
 */
class ScreenCaptureService : Service() {

    private val handlerThread = HandlerThread("capture").apply { start() }
    private val handler = Handler(handlerThread.looper)

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var running = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // The user revoked capture (or the system tore it down); end the session cleanly.
            stopCapture()
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> {
                stopCapture()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent) {
        if (running) return
        // Must be foreground before the projection is touched on API 34+.
        startForegroundWithNotification()

        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (data == null) {
            Diag.e(TAG, "no consent data in start intent")
            stopSelf()
            return
        }
        // On API 34+ this throws SecurityException when the foreground service is not yet in the
        // mediaProjection state, or when the consent token was already consumed. Log it loudly
        // instead of dying silently — that silent failure was the "capture never starts" symptom.
        val media = try {
            manager.getMediaProjection(resultCode, data)
        } catch (t: Throwable) {
            Diag.e(TAG, "getMediaProjection failed", t)
            stopSelf()
            return
        }
        if (media == null) {
            Diag.e(TAG, "getMediaProjection returned null")
            stopSelf()
            return
        }
        media.registerCallback(projectionCallback, handler)
        projection = media
        try {
            startCapture(media)
            running = true
            // The runtime is the single source of truth for "capture is live" — the service's own
            // flag is private. Without this the UI button never flips and every frame is dropped
            // by SolverRuntime.onFrame's running check, which is exactly what happened before.
            SolverRuntime.start()
            Diag.i(TAG, "capture started")
        } catch (t: Throwable) {
            Diag.e(TAG, "startCapture failed", t)
            stopSelf()
        }
    }

    private fun startCapture(media: MediaProjection) {
        val metrics = realScreenMetrics()
        val width = metrics.first
        val height = metrics.second
        val density = resources.displayMetrics.densityDpi

        val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader = imageReader

        imageReader.setOnImageAvailableListener({ r ->
            val image = try {
                r.acquireLatestImage()
            } catch (t: Throwable) {
                Diag.e(TAG, "acquireLatestImage failed", t)
                null
            }
            if (image == null) {
                // Seen on some devices when the virtual display size does not match the real
                // framebuffer; log it so the report shows the mismatch instead of silent silence.
                Diag.w(TAG, "frame callback with no image (size=${width}x$height)")
                return@setOnImageAvailableListener
            }
            try {
                SolverRuntime.onFrame(image, width, height)
            } catch (t: Throwable) {
                // Never let a bad frame kill the capture thread — but record why it was bad.
                Diag.e(TAG, "onFrame failed", t)
            } finally {
                image.close()
            }
        }, handler)

        virtualDisplay = media.createVirtualDisplay(
            "blockblast-capture",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface,
            null,
            handler,
        )
        Diag.i(TAG, "virtual display created ${width}x$height density=$density")
    }

    private fun realScreenMetrics(): Pair<Int, Int> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            dm.widthPixels to dm.heightPixels
        }
    }

    private fun startForegroundWithNotification() {
        val channelId = CHANNEL_ID
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                getString(R.string.notification_channel_capture),
                NotificationManager.IMPORTANCE_LOW,
            )
            manager.createNotificationChannel(channel)
        }
        val notification: Notification = Notification.Builder(this, channelId)
            .setContentTitle(getString(R.string.notification_capture_title))
            .setContentText(getString(R.string.notification_capture_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopCapture() {
        running = false
        virtualDisplay?.release()
        virtualDisplay = null
        reader?.setOnImageAvailableListener(null, null)
        reader?.close()
        reader = null
        projection?.unregisterCallback(projectionCallback)
        projection?.stop()
        projection = null
        SolverRuntime.onCaptureStopped()
    }

    override fun onDestroy() {
        stopCapture()
        handlerThread.quitSafely()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ScreenCapture"
        private const val CHANNEL_ID = "blockblast-capture"
        private const val NOTIFICATION_ID = 7101
        const val ACTION_START = "com.blockblast.solver.capture.START"
        const val ACTION_STOP = "com.blockblast.solver.capture.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        fun startIntent(context: Context, resultCode: Int, data: Intent): Intent =
            Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }

        fun stopIntent(context: Context): Intent =
            Intent(context, ScreenCaptureService::class.java).apply { action = ACTION_STOP }
    }
}
