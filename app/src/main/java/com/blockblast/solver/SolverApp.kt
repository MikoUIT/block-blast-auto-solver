package com.blockblast.solver

import android.app.Application
import com.blockblast.solver.diag.Diag

/**
 * Application entry point. Its only job right now is to install the crash handler as early as
 * possible, so a crash during startup is still captured in the report buffer.
 */
class SolverApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Diag.installCrashHandler()
        Diag.i("App", "started, version=${packageManager.getPackageInfo(packageName, 0).versionName}")
    }
}
