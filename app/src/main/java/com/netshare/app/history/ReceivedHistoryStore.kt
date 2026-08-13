package com.netshare.app.history

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.netshare.app.debug.AgentDebugLog
import org.json.JSONArray
import org.json.JSONObject

data class ReceivedFileRecord(
    val name: String,
    val sizeBytes: Long,
    /** true once file bytes are saved locally; false when only the share list arrived. */
    val downloaded: Boolean,
    /** content:// or file:// URI when saved on device; null if list-only. */
    val localUri: String? = null
)

data class ReceivedSessionRecord(
    val id: String,
    val shareCode: String,
    val receivedAtEpochMs: Long,
    val files: List<ReceivedFileRecord>
) {
    val fileCount: Int get() = files.size
    val downloadedCount: Int get() = files.count { it.downloaded }
}

class ReceivedHistoryStore(context: Context) {
    private val prefs: SharedPreferences? = openPrefs(context.applicationContext)
    val encryptionAvailable: Boolean get() = prefs != null

    fun list(): List<ReceivedSessionRecord> {
        val raw = prefs?.getString(KEY, "[]") ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val filesArr = o.optJSONArray("files") ?: JSONArray()
                    val files = buildList {
                        for (j in 0 until filesArr.length()) {
                            val f = filesArr.getJSONObject(j)
                            add(
                                ReceivedFileRecord(
                                    name = f.getString("n"),
                                    sizeBytes = f.optLong("s", -1L),
                                    downloaded = f.optBoolean("d", false),
                                    localUri = f.optString("u").takeIf { it.isNotBlank() }
                                )
                            )
                        }
                    }
                    add(
                        ReceivedSessionRecord(
                            id = o.getString("id"),
                            shareCode = o.getString("code"),
                            receivedAtEpochMs = o.getLong("at"),
                            files = files
                        )
                    )
                }
            }.sortedByDescending { it.receivedAtEpochMs }
        }.getOrDefault(emptyList())
    }

    fun upsertSession(session: ReceivedSessionRecord) {
        val store = prefs ?: run {
            // #region agent log
            AgentDebugLog.log(
                hypothesisId = "H5",
                location = "ReceivedHistoryStore.upsertSession",
                message = "skipped persist — encryption unavailable",
                data = mapOf("fileCount" to session.files.size),
                runId = "fix-residuals"
            )
            // #endregion
            return
        }
        val current = list().toMutableList()
        // Match on stable topicId only — redacted share codes can collide across sessions.
        val idx = current.indexOfFirst { it.id == session.id }
        if (idx >= 0) {
            val existing = current[idx]
            val mergedFiles = session.files.map { incoming ->
                val prior = existing.files.find { it.name == incoming.name }
                when {
                    prior == null -> incoming
                    prior.downloaded -> incoming.copy(
                        downloaded = true,
                        localUri = incoming.localUri ?: prior.localUri
                    )
                    else -> incoming.copy(localUri = incoming.localUri ?: prior.localUri)
                }
            }
            current[idx] = session.copy(
                id = existing.id,
                receivedAtEpochMs = existing.receivedAtEpochMs,
                files = mergedFiles
            )
            // #region agent log
            AgentDebugLog.log(
                hypothesisId = "H5",
                location = "ReceivedHistoryStore.upsertSession",
                message = "merged history session",
                data = mapOf(
                    "fileCount" to mergedFiles.size,
                    "preservedDownloads" to mergedFiles.count { it.downloaded }
                ),
                runId = "fix-residuals"
            )
            // #endregion
        } else {
            current.add(0, session)
        }
        save(store, current.take(50))
    }

    fun clear() {
        prefs?.edit()?.putString(KEY, "[]")?.apply()
    }

    private fun save(store: SharedPreferences, sessions: List<ReceivedSessionRecord>) {
        val arr = JSONArray()
        sessions.forEach { s ->
            val files = JSONArray()
            s.files.forEach { f ->
                files.put(
                    JSONObject()
                        .put("n", f.name)
                        .put("s", f.sizeBytes)
                        .put("d", f.downloaded)
                        .put("u", f.localUri ?: "")
                )
            }
            arr.put(
                JSONObject()
                    .put("id", s.id)
                    .put("code", s.shareCode)
                    .put("at", s.receivedAtEpochMs)
                    .put("files", files)
            )
        }
        store.edit().putString(KEY, arr.toString()).apply()
    }

    companion object {
        private const val PREFS = "easyshare_received_history_enc"
        private const val LEGACY_PREFS = "easyshare_received_history"
        private const val KEY = "sessions"

        /** Display form that avoids storing the full live share code. */
        fun redactShareCode(code: String): String {
            val normalized = code.trim().uppercase()
            if (normalized.length < 8) return "••••"
            return normalized.take(4) + "••••" + normalized.takeLast(4)
        }

        private fun openPrefs(context: Context): SharedPreferences? {
            return try {
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                val encrypted = EncryptedSharedPreferences.create(
                    context,
                    PREFS,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
                migrateLegacyIfNeeded(context, encrypted)
                encrypted
            } catch (t: Exception) {
                // Fail closed: do not fall back to plaintext prefs.
                // #region agent log
                AgentDebugLog.log(
                    hypothesisId = "H5",
                    location = "ReceivedHistoryStore.openPrefs",
                    message = "encrypted history unavailable — fail closed",
                    data = mapOf("error" to (t.message ?: t.javaClass.simpleName)),
                    runId = "fix-residuals"
                )
                // #endregion
                null
            }
        }

        private fun migrateLegacyIfNeeded(context: Context, encrypted: SharedPreferences) {
            if (encrypted.contains(KEY)) return
            val legacy = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
            val raw = legacy.getString(KEY, null) ?: return
            encrypted.edit().putString(KEY, raw).apply()
            legacy.edit().remove(KEY).apply()
        }
    }
}
