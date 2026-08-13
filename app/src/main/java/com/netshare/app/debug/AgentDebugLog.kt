package com.netshare.app.debug

import android.util.Log
import com.netshare.app.BuildConfig
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Debug-session NDJSON logger. Posts to host ingest via adb reverse:
 * adb reverse tcp:7600 tcp:7600
 *
 * No-ops in non-debug builds.
 */
object AgentDebugLog {
    private const val TAG = "EasyShareDebug"
    private const val ENDPOINT =
        "http://127.0.0.1:7600/ingest/973d4384-6b17-4f64-98c6-5bfedb86f430"
    private const val SESSION = "819aed"
    private val executor = Executors.newSingleThreadExecutor()

    fun log(
        hypothesisId: String,
        location: String,
        message: String,
        data: Map<String, Any?> = emptyMap(),
        runId: String = "review1"
    ) {
        if (!BuildConfig.DEBUG) return
        // #region agent log
        val payload = JSONObject()
            .put("sessionId", SESSION)
            .put("runId", runId)
            .put("hypothesisId", hypothesisId)
            .put("location", location)
            .put("message", message)
            .put("timestamp", System.currentTimeMillis())
            .put("data", JSONObject(data))
        Log.i(TAG, payload.toString())
        executor.execute {
            try {
                val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("X-Debug-Session-Id", SESSION)
                    doOutput = true
                    connectTimeout = 1500
                    readTimeout = 1500
                }
                OutputStreamWriter(conn.outputStream).use { it.write(payload.toString()) }
                conn.responseCode
                conn.disconnect()
            } catch (_: Exception) {
                // Host ingest may be unavailable; Logcat still has the line.
            }
        }
        // #endregion
    }
}
