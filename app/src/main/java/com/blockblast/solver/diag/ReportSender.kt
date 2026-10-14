package com.blockblast.solver.diag

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Files a diagnostic report as a GitHub issue.
 *
 * The write token is supplied by the user in the app and kept in private preferences — it is never
 * compiled into the APK nor committed. On first use the repository is expected to exist; if the
 * token lacks access the API returns 401/403 and the caller surfaces that plainly.
 *
 * A workaround is worth noting: this app's package is not a GitHub App, so it cannot write an issue
 * anonymously. The token is the only mechanism, and that is the trade-off the user accepted.
 */
object ReportSender {

    private const val OWNER = "MikoUIT"
    private const val REPO = "block-blast-auto-solver"
    private const val PREFS = "solver_prefs"
    private const val KEY_TOKEN = "github_report_token"

    /** Stores the fine-grained write token in private storage. Empty clears it. */
    fun saveToken(context: Context, token: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TOKEN, token.trim())
            .apply()
    }

    fun loadToken(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TOKEN, "") ?: ""

    fun hasToken(context: Context): Boolean = loadToken(context).isNotBlank()

    /** Result of a send attempt, with the created issue URL on success. */
    sealed interface Result {
        data class Sent(val issueUrl: String) : Result
        data class Failed(val message: String) : Result
        data object NoToken : Result
    }

    /**
     * Builds the report body and creates an issue. Network work must call this off the main thread.
     */
    fun send(context: Context, userNote: String): Result {
        val token = loadToken(context)
        if (token.isBlank()) return Result.NoToken

        val title = buildTitle()
        val body = buildBody(context, userNote)
        return try {
            postIssue(token, title, body)
        } catch (t: Throwable) {
            Result.Failed("Не удалось создать issue: ${t.message ?: t::class.simpleName}")
        }
    }

    private fun buildTitle(): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        return "Отчёт с устройства — $stamp"
    }

    private fun buildBody(context: Context, userNote: String): String = buildString {
        if (userNote.isNotBlank()) {
            appendLine("### Комментарий")
            appendLine(userNote.trim())
            appendLine()
        }
        appendLine(DeviceInfo.collect(context))
        appendLine()
        appendLine("### Логи")
        appendLine("```")
        val lines = Diag.snapshot()
        if (lines.isEmpty()) {
            appendLine("(пусто)")
        } else {
            lines.forEach { appendLine(it) }
        }
        appendLine("```")
    }

    private fun postIssue(token: String, title: String, body: String): Result {
        val url = URL("https://api.github.com/repos/$OWNER/$REPO/issues")
        val payload = JSONObject().apply {
            put("title", title)
            put("body", body)
        }.toString()

        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10000
            readTimeout = 15000
            doOutput = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("User-Agent", "BlockBlastSolver-Report")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        return try {
            connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code == 201) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val issueUrl = JSONObject(response).optString("html_url")
                Diag.i("Report", "issue created: $issueUrl")
                Result.Sent(issueUrl)
            } else {
                val error = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                val hint = when (code) {
                    401 -> "токен неверный или отозван"
                    403 -> "у токена нет прав Issues:write"
                    404 -> "репозиторий не найден или токен без доступа к нему"
                    else -> "HTTP $code"
                }
                Diag.e("Report", "issue failed: $code $error")
                Result.Failed("$hint (HTTP $code)")
            }
        } finally {
            connection.disconnect()
        }
    }
}
