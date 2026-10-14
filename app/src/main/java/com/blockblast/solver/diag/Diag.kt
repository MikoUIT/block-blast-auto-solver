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

    private const val MAX_LINES = 1200

    /**
     * Hard cap on the total characters kept in the ring. GitHub rejects issue bodies over 65536
     * characters, so the log alone must never fill that budget — reports also carry device info and
     * image links. Lines are dropped from the oldest end until the total fits.
     */
    private const val MAX_CHARS = 40000

    private val ring = ConcurrentLinkedDeque<String>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** Running character count of [ring], so trimming stays O(1) per append. */
    @Volatile
    private var ringChars = 0

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
        // Cap a single line too: a stack trace can be thousands of characters on its own.
        val line = "${timeFormat.format(Date())} $level/$tag: ${message.take(2000)}"
        synchronized(this) {
            ring.addLast(line)
            ringChars += line.length + 1
            while (ring.size > MAX_LINES || (ringChars > MAX_CHARS && ring.size > 1)) {
                val removed = ring.pollFirst() ?: break
                ringChars -= removed.length + 1
            }
        }
    }

    /** Snapshot of the buffered log lines, oldest first. */
    fun snapshot(): List<String> = ring.toList()

    fun clear() = synchronized(this) {
        ring.clear()
        ringChars = 0
    }
}
