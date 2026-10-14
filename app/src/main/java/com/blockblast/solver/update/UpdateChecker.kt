package com.blockblast.solver.update

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks GitHub Releases for a newer build of this app.
 *
 * The repo is public, so no token is needed: the `/releases/latest` endpoint returns the newest
 * published release as JSON, including a tag and the APK asset URL. We compare the release's
 * `versionCode` marker against the running build and hand back the download link when it is newer.
 *
 * All network work happens off the main thread (see [MainActivity]); this class only does the HTTP
 * call and parsing.
 */
object UpdateChecker {

    /** GitHub coordinates for the releases feed. */
    private const val OWNER = "MikoUIT"
    private const val REPO = "block-blast-auto-solver"
    private const val API = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"

    /** Result of a check. */
    sealed interface Result {
        /** Running build is the latest. */
        data class UpToDate(val current: String) : Result

        /** A newer build exists. [downloadUrl] points at the APK asset. */
        data class UpdateAvailable(
            val current: String,
            val latest: String,
            val downloadUrl: String,
            val notes: String,
        ) : Result

        /** The check could not complete; [message] is user-facing. */
        data class Failed(val message: String) : Result
    }

    /**
     * Queries the latest release and compares it with [currentVersionCode].
     *
     * The release tag carries the version code as a trailing number (e.g. `v20261014-2011`), but the
     * reliable signal is the build's own `versionCode`, which the release note echoes as
     * `v<N>`. When the tag can't be parsed we fall back to treating any release as newer only if its
     * name differs from the running version string.
     */
    fun check(currentVersionCode: Int, currentVersionName: String): Result {
        return try {
            val connection = (URL(API).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "BlockBlastSolver-Updater")
            }
            try {
                val code = connection.responseCode
                if (code == 404) {
                    return Result.UpToDate(currentVersionName)
                }
                if (code != 200) {
                    return Result.Failed("GitHub ответил $code")
                }
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                parse(body, currentVersionCode, currentVersionName)
            } finally {
                connection.disconnect()
            }
        } catch (t: Throwable) {
            Result.Failed("Нет связи с GitHub: ${t.message ?: t::class.simpleName}")
        }
    }

    private fun parse(body: String, currentVersionCode: Int, currentVersionName: String): Result {
        val json = JSONObject(body)
        val tag = json.optString("tag_name", "")
        val notes = json.optString("body", "")
        val publishName = json.optString("name", tag)

        val remoteCode = extractVersionCode(tag)
        val apkUrl = findApkAsset(json)

        val newer = when {
            remoteCode != null -> remoteCode > currentVersionCode
            else -> publishName.isNotBlank() && publishName != currentVersionName
        }
        return if (newer && apkUrl != null) {
            Result.UpdateAvailable(
                current = currentVersionName,
                latest = publishName.ifBlank { tag },
                downloadUrl = apkUrl,
                notes = notes,
            )
        } else {
            Result.UpToDate(currentVersionName)
        }
    }

    /**
     * Pulls the version code out of a tag like `v2-0.2.0`. The release script always emits this
     * exact shape, so the leading `v<code>` segment is the authoritative numeric comparison.
     */
    private fun extractVersionCode(tag: String): Int? {
        val core = tag.removePrefix("v")
        val head = core.substringBefore('-')
        return head.toIntOrNull()
    }

    private fun findApkAsset(json: JSONObject): String? {
        val assets = json.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            if (asset.optString("name").endsWith(".apk", ignoreCase = true)) {
                return asset.optString("browser_download_url").ifBlank { null }
            }
        }
        return null
    }
}
