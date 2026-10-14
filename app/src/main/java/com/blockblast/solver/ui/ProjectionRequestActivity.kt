package com.blockblast.solver.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.blockblast.solver.capture.ScreenCaptureService

/**
 * Asks the system for screen-capture consent and hands the token to [ScreenCaptureService].
 *
 * Android 14 (API 34) is strict about two things here, and both bit us:
 *
 *  1. **The requesting Activity must be a real, opaque, visible window.** A translucent theme makes
 *     the system drop the prompt on the floor without an error. We use a plain opaque screen.
 *  2. **The prompt two-step is fragile in onCreate/onResume.** Firing it too early — while the
 *     window is still DRAW_PENDING — can make the dialog never appear. We wait for the first
 *     `onWindowFocusChanged`, which guarantees the window is actually on screen, and only then
 *     launch the request. A one-shot guard stops it firing twice.
 *
 * The Activity does NOT finish until the system answers, and it is deliberately not `noHistory`:
 * with `noHistory` the Activity is destroyed the moment the consent dialog covers it, so
 * `onActivityResult` never arrives and capture silently never starts.
 */
class ProjectionRequestActivity : Activity() {

    private val requestCode = 4711
    private var requested = false
    private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A minimal opaque body so the Activity is unambiguously visible to the system.
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#1B1B1F"))
            val label = TextView(this@ProjectionRequestActivity).apply {
                text = "Запрос доступа к экрану…"
                setTextColor(Color.WHITE)
                textSize = 16f
            }
            addView(label)
        }
        setContentView(root)
        Log.i(TAG, "created")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Launch only once the window truly has focus — that is the earliest point the system will
        // honour a MediaProjection prompt on API 34+.
        if (hasFocus && !requested) {
            requested = true
            launchConsent()
        }
    }

    private fun launchConsent() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        if (manager == null) {
            Log.e(TAG, "MediaProjectionManager unavailable")
            Toast.makeText(this, "MediaProjection недоступен на этом устройстве", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val intent = manager.createScreenCaptureIntent()
        Log.i(TAG, "launching consent dialog")
        try {
            startActivityForResult(intent, requestCode)
        } catch (t: Throwable) {
            Log.e(TAG, "createScreenCaptureIntent failed", t)
            Toast.makeText(this, "Не удалось вызвать диалог захвата", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        Log.i(TAG, "consent result: requestCode=$requestCode resultCode=$resultCode data=${data != null} handled=$handled")
        if (handled) return
        handled = true
        if (requestCode != this.requestCode) {
            finish()
            return
        }
        if (resultCode == RESULT_OK && data != null) {
            val intent = ScreenCaptureService.startIntent(this, resultCode, data)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
                Log.i(TAG, "capture service start requested")
            } catch (t: Throwable) {
                Log.e(TAG, "failed to start capture service", t)
                Toast.makeText(this, "Не удалось запустить сервис захвата", Toast.LENGTH_LONG).show()
            }
        } else {
            Log.w(TAG, "consent denied or empty (resultCode=$resultCode)")
            Toast.makeText(this, "Захват экрана отклонён", Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    private companion object {
        const val TAG = "ProjectionRequest"
    }
}
