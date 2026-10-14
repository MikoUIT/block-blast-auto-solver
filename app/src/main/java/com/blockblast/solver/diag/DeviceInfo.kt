package com.blockblast.solver.diag

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.WindowManager
import com.blockblast.solver.automation.TapAccessibilityService
import com.blockblast.solver.core.SolverRuntime

/**
 * Gathers the environment facts that make a bug report actionable: device, build, screen, granted
 * permissions and the last solver state. Collected fresh at send time so the report reflects the
 * moment the user pressed the button.
 */
object DeviceInfo {

    fun collect(context: Context): String = buildString {
        appendLine("### Окружение")
        appendLine("- Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("- Устройство: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("- Прошивка: ${Build.DISPLAY}")
        appendLine("- ABI: ${Build.SUPPORTED_ABIS.joinToString()}")

        val (w, h, dpi) = screen(context)
        appendLine("- Экран: ${w}x${h}, ${dpi} dpi")

        appendLine()
        appendLine("### Состояние приложения")
        appendLine("- Захват запущен: ${SolverRuntime.isRunning()}")
        appendLine("- Режим: ${SolverRuntime.mode}")
        appendLine("- Целевой FPS: ${SolverRuntime.targetFps}")
        appendLine("- Оверлей разрешён: ${Settings.canDrawOverlays(context)}")
        appendLine("- Управление подключено: ${TapAccessibilityService.isConnected()}")
    }

    private fun screen(context: Context): Triple<Int, Int, Int> {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            val dpi = context.resources.displayMetrics.densityDpi
            Triple(bounds.width(), bounds.height(), dpi)
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            Triple(dm.widthPixels, dm.heightPixels, dm.densityDpi)
        }
    }
}
