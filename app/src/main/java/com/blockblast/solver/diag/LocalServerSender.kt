package com.blockblast.solver.diag

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Sends a diagnostic report to the local desktop receiver instead of GitHub.
 *
 * This is the primary reporting path: it needs no token, no repository permissions and no internet
 * — only the phone and the PC on the same Wi-Fi. The receiver (`tools/report-server.js`) writes the
 * report and its snapshots into a timestamped folder, where the developer reads them directly off
 * disk. GitHub reporting remains as a fallback for when the PC is not running.
 */
object LocalServerSender {

    /**
     * The desktop receiver address. Hard-coded per LO's request. Port 8791 because 8787/8789 are
     * occupied by a local dev proxy on his machine (it binds 0.0.0.0 and intercepts loopback).
     */
    const val ENDPOINT = "http://192.168.0.139:8791/report"

    sealed interface Result {
        data class Sent(val folder: String) : Result
        data class Failed(val message: String) : Result
    }

    /** Posts the report as JSON. Must be called off the main thread. */
    fun send(context: Context, userNote: String): Result {
        val payload = buildPayload(context, userNote)
        val bytes = payload.toString().toByteArray(Charsets.UTF_8)

        val connection = try {
            (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 6000
                readTimeout = 20000
                doOutput = true
                // Explicit fixed-length streaming: without this, a large body (the snapshots) can
                // be sent chunked or truncated, and some servers/stack layers silently drop it.
                setFixedLengthStreamingMode(bytes.size)
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("User-Agent", "BlockBlastSolver-Local")
            }
        } catch (t: Throwable) {
            return Result.Failed("Не открыть ${ENDPOINT}: ${t.message ?: t::class.simpleName}")
        }

        return try {
            connection.outputStream.use { it.write(bytes) }
            val code = connection.responseCode
            if (code in 200..299) {
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val folder = runCatching { JSONObject(body).optString("folder") }.getOrDefault("")
                Diag.i("LocalReport", "sent to $ENDPOINT ($code) folder=$folder")
                Result.Sent(folder.ifBlank { "$ENDPOINT" })
            } else {
                val error = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                Diag.e("LocalReport", "server returned $code $error")
                Result.Failed("сервер ответил HTTP $code")
            }
        } catch (t: Throwable) {
            Diag.e("LocalReport", "send failed", t)
            Result.Failed("${t::class.simpleName}: ${t.message ?: "нет связи с ПК"}")
        } finally {
            connection.disconnect()
        }
    }

    private fun buildPayload(context: Context, userNote: String): JSONObject {
        val json = JSONObject()
        json.put("note", userNote)
        json.put("deviceInfo", DeviceInfo.collect(context))
        json.put("log", Diag.snapshot().joinToString("\n"))

        val frames = JSONArray()
        for (frame in SnapshotCollector.all()) {
            val views = JSONArray()
            for (view in frame.views) {
                views.put(JSONObject().apply {
                    put("name", view.name)
                    put("jpeg", view.jpegBase64)
                })
            }
            frames.put(
                JSONObject().apply {
                    put("label", frame.label)
                    put("views", views)
                },
            )
        }
        json.put("snapshots", frames)
        return json
    }
}
