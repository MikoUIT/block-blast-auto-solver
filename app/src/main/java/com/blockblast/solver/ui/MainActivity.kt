package com.blockblast.solver.ui

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.blockblast.solver.R
import com.blockblast.solver.automation.AutoPlayer
import com.blockblast.solver.automation.TapAccessibilityService
import com.blockblast.solver.capture.ScreenCaptureService
import com.blockblast.solver.core.SolverRuntime
import com.blockblast.solver.databinding.ActivityMainBinding
import com.blockblast.solver.diag.Diag
import com.blockblast.solver.diag.ReportSender
import com.blockblast.solver.overlay.OverlayService
import com.blockblast.solver.update.UpdateChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The control panel. Starts/stops capture, switches mode, toggles the debug layer and reports
 * whether the two required permissions (overlay + accessibility) are in place.
 *
 * All the interesting work happens elsewhere; this class only wires user intent into
 * [SolverRuntime] and the two services.
 */
class MainActivity : AppCompatActivity(), SolverRuntime.Listener {

    private lateinit var binding: ActivityMainBinding
    private val autoPlayer = AutoPlayer()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnOverlayPermission.setOnClickListener { requestOverlayPermission() }
        binding.btnAccessibility.setOnClickListener { openAccessibilitySettings() }
        binding.btnToggleCapture.setOnClickListener { toggleCapture() }
        binding.btnCheckUpdate.setOnClickListener { checkForUpdates() }
        binding.btnSendReport.setOnClickListener { sendReport() }
        binding.inputToken.setText(ReportSender.loadToken(this))
        binding.textVersion.text = getString(R.string.ui_version_fmt, currentVersionName(), currentVersionCode())
        binding.modeGroup.setOnCheckedChangeListener { _, checkedId ->
            SolverRuntime.mode = if (checkedId == binding.modeSolver.id) {
                SolverRuntime.Mode.SOLVER
            } else {
                SolverRuntime.Mode.HINT
            }
            updateToggleLabel()
        }
        binding.checkDebug.setOnCheckedChangeListener { _, checked ->
            sendOverlayCommand(OverlayService.ACTION_SET_DEBUG, checked)
            sendOverlayCommand(OverlayService.ACTION_SET_HINT, checked)
        }
    }

    private fun currentVersionName(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        }

    private fun currentVersionCode(): Long {
        val info = packageManager.getPackageInfo(packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    }

    override fun onResume() {
        super.onResume()
        SolverRuntime.addListener(this)
        if (SolverRuntime.mode == SolverRuntime.Mode.SOLVER) autoPlayer.attach()
        refreshPermissionStatus()
        updateToggleLabel()
    }

    override fun onPause() {
        SolverRuntime.removeListener(this)
        autoPlayer.detach()
        super.onPause()
    }

    private fun toggleCapture() {
        if (SolverRuntime.isRunning()) {
            stopCapture()
        } else {
            Diag.i(TAG, "requesting projection consent")
            startActivity(Intent(this, ProjectionRequestActivity::class.java))
            // Bring the overlay up alongside capture so the debug layer is visible immediately.
            if (Settings.canDrawOverlays(this)) {
                startService(OverlayService.startIntent(this))
            } else {
                Toast.makeText(this, "Сначала разреши оверлей", Toast.LENGTH_LONG).show()
                requestOverlayPermission()
            }
        }
    }

    private fun stopCapture() {
        startService(ScreenCaptureService.stopIntent(this))
        startService(OverlayService.stopIntent(this))
        Toast.makeText(this, "Захват остановлен", Toast.LENGTH_SHORT).show()
    }

    private fun updateToggleLabel() {
        binding.btnToggleCapture.text = getString(
            if (SolverRuntime.isRunning()) R.string.ui_stop_capture else R.string.ui_start_capture,
        )
    }

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName"),
        )
        startActivity(intent)
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        Toast.makeText(this, "Включи «Block Blast Solver» в списке служб", Toast.LENGTH_LONG).show()
    }

    private fun sendOverlayCommand(action: String, enabled: Boolean) {
        val intent = Intent(this, OverlayService::class.java).apply {
            this.action = action
            putExtra(OverlayService.EXTRA_ENABLED, enabled)
        }
        startService(intent)
    }

    private fun refreshPermissionStatus() {
        val overlayOk = Settings.canDrawOverlays(this)
        val accessOk = TapAccessibilityService.isConnected()
        binding.statusPermissions.text = buildString {
            append(if (overlayOk) getString(R.string.ui_perm_overlay_ok) else getString(R.string.ui_perm_overlay_no))
            append('\n')
            append(if (accessOk) getString(R.string.ui_perm_access_ok) else getString(R.string.ui_perm_access_no))
        }
    }

    override fun onSnapshot(snapshot: SolverRuntime.Snapshot) {
        runOnUiThread {
            binding.statusRuntime.text = buildString {
                append("кадр ").append(snapshot.frameId)
                append("  ").append(snapshot.solveMillis).append(" ms\n")
                append("фигур: ").append(snapshot.recognizedPieces.size)
                append(" (совпало ").append(snapshot.piecesMatched).append(")\n")
                append("занято клеток: ").append(snapshot.board.filledCount()).append('\n')
                snapshot.suggestion?.let { s ->
                    append("ход: чистит ").append(s.linesCleared)
                    append(", счёт ~").append("%.0f".format(s.projectedScore)).append('\n')
                    s.rationale.forEach { append("• ").append(it).append('\n') }
                }
            }
            updateToggleLabel()
        }
    }

    override fun onCaptureStopped() {
        runOnUiThread { updateToggleLabel() }
    }

    override fun onCaptureError(message: String) {
        runOnUiThread { binding.statusRuntime.text = message }
    }

    // --- updates -----------------------------------------------------------------------------

    /**
     * Asks GitHub for the latest release and, if it is newer, downloads its APK and opens the system
     * installer. Everything runs off a background coroutine so the UI thread stays responsive.
     */
    private fun checkForUpdates() {
        val button = binding.btnCheckUpdate
        button.isEnabled = false
        button.text = getString(R.string.ui_update_checking)
        val code = currentVersionCode().toInt()
        val name = currentVersionName()

        CoroutineScope(Dispatchers.IO).launch {
            val result = UpdateChecker.check(code, name)
            withContext(Dispatchers.Main) {
                button.isEnabled = true
                button.text = getString(R.string.ui_check_update)
                when (result) {
                    is UpdateChecker.Result.UpToDate ->
                        Toast.makeText(this@MainActivity, R.string.ui_update_current, Toast.LENGTH_SHORT).show()

                    is UpdateChecker.Result.UpdateAvailable -> {
                        Toast.makeText(
                            this@MainActivity,
                            getString(R.string.ui_update_available, result.latest),
                            Toast.LENGTH_LONG,
                        ).show()
                        downloadAndInstall(result.downloadUrl)
                    }

                    is UpdateChecker.Result.Failed ->
                        Toast.makeText(
                            this@MainActivity,
                            getString(R.string.ui_update_failed, result.message),
                            Toast.LENGTH_LONG,
                        ).show()
                }
            }
        }
    }

    /**
     * Saves whatever token the user typed, then files a report as a GitHub issue on a background
     * thread. The token stays in private preferences and is never written to the APK or git.
     */
    private fun sendReport() {
        val token = binding.inputToken.text?.toString()?.trim().orEmpty()
        if (token.isBlank()) {
            Toast.makeText(this, R.string.ui_report_no_token, Toast.LENGTH_LONG).show()
            return
        }
        ReportSender.saveToken(this, token)
        val note = binding.inputReportNote.text?.toString().orEmpty()

        binding.btnSendReport.isEnabled = false
        binding.textReportStatus.text = getString(R.string.ui_report_sending)
        Diag.i(TAG, "sending report, note=${note.take(60)}")

        CoroutineScope(Dispatchers.IO).launch {
            val result = ReportSender.send(this@MainActivity, note)
            withContext(Dispatchers.Main) {
                binding.btnSendReport.isEnabled = true
                binding.textReportStatus.text = when (result) {
                    is ReportSender.Result.Sent ->
                        getString(R.string.ui_report_sent, result.issueUrl)
                    is ReportSender.Result.Failed ->
                        getString(R.string.ui_report_failed, result.message)
                    ReportSender.Result.NoToken ->
                        getString(R.string.ui_report_no_token)
                }
            }
        }
    }

    private fun downloadAndInstall(url: String) {
        if (!packageManager.canRequestPackageInstalls()) {
            Toast.makeText(this, R.string.ui_update_no_install, Toast.LENGTH_LONG).show()
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName"),
                ),
            )
            return
        }
        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setTitle("Block Blast Solver")
            setDescription("Скачивание обновления")
            setMimeType("application/vnd.android.package-archive")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                "blockblast-solver-update.apk",
            )
        }
        val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        manager.enqueue(request)
        Toast.makeText(this, "Загрузка началась — откроется установщик", Toast.LENGTH_LONG).show()
    }

    private companion object {
        const val TAG = "MainActivity"
    }
}
