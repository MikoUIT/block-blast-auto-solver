package com.blockblast.solver.diag

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * In-memory log ring plus crash capture, feeding the "send report" button.
 *
 * Only this app's own voice can be captured without root — the system log is off limits to
 * unprivileged apps since Android 4.1. That is fine here: every line that matters is ours, so this
 * facade is the single place we log through, writing both to logcat (for adb) and a bounded ring
 * buffer (for the report). Keeping one entry point is what makes the report reliable.
 */
object Diag {

    private const val MAX_LINES = 1500
    private val ring = ConcurrentLinkedDeque<String>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var crashHandler: Thread.UncaughtExceptionHandler? = null

    /** Installs a crash hook that records the throwable into the ring before the process dies. */
    fun installCrashHandler() {
        if (crashHandler != null) return
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val handler = Thread.UncaughtExceptionHandler { thread, throwable ->
            append("E", "Crash", "uncaught in ${thread.name}: ${throwable.stackTraceToString()}")
            previous?.uncaughtException(thread, throwable)
        }
        Thread.setDefaultUncaughtExceptionHandler(handler)
        crashHandler = handler
    }

    fun d(tag: String, message: String) {
        Log.d(tag, message)
        append("D", tag, message)
    }

    fun i(tag: String, message: String) {
        Log.i(tag, message)
        append("I", tag, message)
    }

    fun w(tag: String, message: String) {
        Log.w(tag, message)
        append("W", tag, message)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable == null) {
            Log.e(tag, message)
            append("E", tag, message)
        } else {
            Log.e(tag, message, throwable)
            append("E", tag, "$message\n${throwable.stackTraceToString()}")
        }
    }

    private fun append(level: String, tag: String, message: String) {
        val line = "${timeFormat.format(Date())} $level/$tag: $message"
        ring.addLast(line)
        while (ring.size > MAX_LINES) ring.pollFirst()
    }

    /** Snapshot of the buffered log lines, oldest first. */
    fun snapshot(): List<String> = ring.toList()

    fun clear() = ring.clear()
}
