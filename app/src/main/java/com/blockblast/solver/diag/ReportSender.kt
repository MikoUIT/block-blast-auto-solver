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

    /**
     * Snapshots are committed to this branch rather than inlined into the issue body.
     *
     * Inlining looked clever — GitHub renders `data:` URIs — but the Issues API rejects oversized
     * bodies with 422, and even one JPEG pair blows past the ~64 KB body limit. So we push the
     * images into a dedicated orphan branch via the Contents API and reference their raw URLs.
     * The branch keeps report debris out of `master`.
     */
    private const val BRANCH = "reports"

    /** The reports branch only needs to be created/checked once per process. */
    @Volatile
    private var branchChecked = false

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
        val body = buildBody(context, userNote, token)
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

    private fun buildBody(context: Context, userNote: String, token: String): String = buildString {
        if (userNote.isNotBlank()) {
            appendLine("### Комментарий")
            appendLine(userNote.trim())
            appendLine()
        }
        appendLine(DeviceInfo.collect(context))
        appendLine()
        val snapshots = appendSnapshots(token)
        if (snapshots.isNotBlank()) {
            append(snapshots)
        } else {
            appendLine("### Снимки")
            appendLine("_Снимков нет — жми кнопку 📷 поверх игры перед отправкой._")
            appendLine()
        }
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

    /**
     * Uploads the captured frames to the `reports` branch and returns markdown that references their
     * raw URLs. Returns an empty string when there is nothing to attach.
     */
    private fun appendSnapshots(token: String): String {
        val frames = SnapshotCollector.all()
        if (frames.isEmpty()) return ""
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

        return buildString {
            appendLine("### Снимки (${frames.size})")
            appendLine()
            for ((index, frame) in frames.withIndex()) {
                val n = index + 1
                val plainUrl = uploadImage(token, "$stamp-$n-plain", frame.plainJpegBase64)
                val markedUrl = uploadImage(token, "$stamp-$n-marked", frame.markedJpegBase64)
                appendLine("**$n. ${frame.label} — оригинал**")
                appendLine(if (plainUrl != null) "![plain$n]($plainUrl)" else "_не загрузилось_")
                appendLine()
                appendLine("**$n. ${frame.label} — с разметкой распознавания**")
                appendLine(if (markedUrl != null) "![marked$n]($markedUrl)" else "_не загрузилось_")
                appendLine()
            }
        }
    }

    /**
     * PUTs a base64 JPEG to `reports/<name>.jpg` and returns its raw URL, or null on failure.
     *
     * The Contents API needs the current file SHA to overwrite; we generate unique names per report
     * so a create always succeeds without a lookup. The branch is created lazily if it is missing.
     */
    private fun uploadImage(token: String, name: String, jpegBase64: String): String? {
        ensureBranch(token)
        val path = "snapshots/$name.jpg"
        val url = URL("https://api.github.com/repos/$OWNER/$REPO/contents/$path")
        val payload = JSONObject().apply {
            put("message", "report snapshot $name")
            put("content", jpegBase64)
            put("branch", BRANCH)
        }.toString()

        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            connectTimeout = 10000
            readTimeout = 30000
            doOutput = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("User-Agent", "BlockBlastSolver-Report")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        return try {
            connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code == 201 || code == 200) {
                "https://raw.githubusercontent.com/$OWNER/$REPO/$BRANCH/$path"
            } else {
                val error = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                Diag.e("Report", "snapshot upload failed: $code $error")
                null
            }
        } catch (t: Throwable) {
            Diag.e("Report", "snapshot upload error for $name", t)
            null
        } finally {
            connection.disconnect()
        }
    }

    /** Creates the reports branch as a child of the default branch if it does not exist yet. */
    private fun ensureBranch(token: String) {
        if (branchChecked) return
        branchChecked = true
        val refUrl = URL("https://api.github.com/repos/$OWNER/$REPO/git/ref/heads/$BRANCH")
        val exists = (refUrl.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("User-Agent", "BlockBlastSolver-Report")
        }.let { conn ->
            try {
                conn.responseCode == 200
            } finally {
                conn.disconnect()
            }
        }
        if (exists) return

        // Resolve the default branch head to branch off from.
        val repoUrl = URL("https://api.github.com/repos/$OWNER/$REPO")
        val headSha = (repoUrl.openConnection() as HttpURLConnection).apply {
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("User-Agent", "BlockBlastSolver-Report")
        }.let { conn ->
            try {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val defaultBranch = JSONObject(body).optString("default_branch", "master")
                fetchBranchHead(token, defaultBranch)
            } finally {
                conn.disconnect()
            }
        } ?: return

        val createUrl = URL("https://api.github.com/repos/$OWNER/$REPO/git/refs")
        val payload = JSONObject().apply {
            put("ref", "refs/heads/$BRANCH")
            put("sha", headSha)
        }.toString()
        (createUrl.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("User-Agent", "BlockBlastSolver-Report")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }.let { conn ->
            try {
                conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                Diag.i("Report", "reports branch created: ${conn.responseCode}")
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun fetchBranchHead(token: String, branch: String): String? {
        val url = URL("https://api.github.com/repos/$OWNER/$REPO/git/ref/heads/$branch")
        return (url.openConnection() as HttpURLConnection).apply {
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("User-Agent", "BlockBlastSolver-Report")
        }.let { conn ->
            try {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                JSONObject(body).getJSONObject("object").optString("sha").ifBlank { null }
            } catch (t: Throwable) {
                Diag.e("Report", "cannot resolve head of $branch", t)
                null
            } finally {
                conn.disconnect()
            }
        }
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
                // Surface GitHub's own message — a bare "HTTP 422" hides which field it rejected.
                val detail = runCatching {
                    JSONObject(error).optJSONArray("errors")
                        ?.let { arr -> (0 until arr.length()).joinToString("; ") { arr.optJSONObject(it)?.toString() ?: "" } }
                        ?: JSONObject(error).optString("message")
                }.getOrNull().orEmpty()
                val hint = when (code) {
                    401 -> "токен неверный или отозван"
                    403 -> "у токена нет прав (нужно Contents:write и Issues:write)"
                    404 -> "нет доступа к репозиторию"
                    422 -> "GitHub отклонил данные: ${detail.ifBlank { "слишком большое тело или неверное поле" }}"
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
