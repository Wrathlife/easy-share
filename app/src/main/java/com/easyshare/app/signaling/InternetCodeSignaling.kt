package com.easyshare.app.signaling

import com.easyshare.app.debug.AgentDebugLog
import com.easyshare.app.webrtc.PairingCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

data class SharedFileInfo(
    val name: String,
    val sizeBytes: Long
)

sealed interface PairingSignalState {
    data object Idle : PairingSignalState
    data object Connecting : PairingSignalState
    data object Waiting : PairingSignalState
    data object Paired : PairingSignalState
    data class Failed(val reason: String) : PairingSignalState
}

/**
 * Internet pairing + encrypted share manifest over MQTTS.
 * File bytes stay P2P later; this only syncs paths/sizes and paired state.
 *
 * Payloads are AES-GCM sealed (broker cannot read filenames). Inner HMAC still binds
 * fields to the share-code-derived auth key. Topic uses a hash of the code.
 */
class InternetCodeSignaling(
    private val scope: CoroutineScope
) {
    private val _state = MutableStateFlow<PairingSignalState>(PairingSignalState.Idle)
    val state: StateFlow<PairingSignalState> = _state.asStateFlow()

    private val _remoteFiles = MutableStateFlow<List<SharedFileInfo>>(emptyList())
    val remoteFiles: StateFlow<List<SharedFileInfo>> = _remoteFiles.asStateFlow()

    private var client: MqttClient? = null
    private var topic: String? = null
    private var role: String? = null
    private var authKey: ByteArray = ByteArray(0)
    private var encKey: ByteArray = ByteArray(0)
    private var sessionExpEpochSec: Long = 0L
    private var guestLocked: Boolean = false
    private var manifestFrozen: Boolean = false
    private var joinRetryJob: Job? = null
    private var connectJob: Job? = null
    private var expiryJob: Job? = null
    private var localManifest: List<SharedFileInfo> = emptyList()
    private val sessionGen = AtomicInteger(0)
    private val seenNonces = ArrayDeque<String>()
    /** Serializes connect + message handling so guestLocked / manifestFrozen stay consistent. */
    private val signalingDispatcher = Dispatchers.IO.limitedParallelism(1)

    fun startHost(code: String, files: List<SharedFileInfo>) {
        localManifest = files.take(MAX_MANIFEST_FILES)
        // #region agent log
        AgentDebugLog.log(
            hypothesisId = "H1",
            location = "InternetCodeSignaling.startHost",
            message = "host manifest sized",
            data = mapOf(
                "requested" to files.size,
                "published" to localManifest.size,
                "capped" to (files.size > MAX_MANIFEST_FILES)
            ),
            runId = "fix-review2"
        )
        // #endregion
        start(code = code, role = "host")
    }

    fun startGuest(code: String) {
        localManifest = emptyList()
        _remoteFiles.value = emptyList()
        start(code = code, role = "guest")
    }

    fun stop() {
        reset(keepManifest = false)
    }

    private fun start(code: String, role: String) {
        reset(keepManifest = true)
        val normalized = PairingCode.normalize(code)
        if (!PairingCode.isValidShort(normalized)) {
            _state.value = PairingSignalState.Failed("Invalid share code format")
            return
        }

        this.role = role
        authKey = SignalingCrypto.authKey(normalized)
        encKey = SignalingCrypto.encKey(normalized)
        sessionExpEpochSec = (System.currentTimeMillis() / 1000L) + SESSION_TTL_SEC
        guestLocked = false
        manifestFrozen = false
        seenNonces.clear()
        topic = "easyshare/v1/${SignalingCrypto.topicId(normalized)}"
        _state.value = PairingSignalState.Connecting
        scheduleExpiryWatch()

        val gen = sessionGen.incrementAndGet()
        // #region agent log
        AgentDebugLog.log(
            hypothesisId = "H2",
            location = "InternetCodeSignaling.start",
            message = "starting encrypted MQTTS signaling",
            data = mapOf(
                "role" to role,
                "codeLen" to normalized.length,
                "manifestCount" to localManifest.size,
                "sessionGen" to gen,
                "broker" to BROKER_URI,
                "ttlSec" to SESSION_TTL_SEC
            ),
            runId = "fix-review2"
        )
        // #endregion

        connectJob?.cancel()
        connectJob = scope.launch(Dispatchers.IO) {
            try {
                val mqtt = MqttClient(
                    BROKER_URI,
                    "es-${role.take(1)}-${UUID.randomUUID()}",
                    MemoryPersistence()
                )
                if (gen != sessionGen.get()) {
                    runCatching { mqtt.close() }
                    return@launch
                }
                client = mqtt
                mqtt.setCallback(object : MqttCallbackExtended {
                    override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                        scope.launch(signalingDispatcher) {
                            if (gen != sessionGen.get()) return@launch
                            onConnected(reconnect = reconnect, gen = gen)
                        }
                    }

                    override fun connectionLost(cause: Throwable?) {
                        // #region agent log
                        AgentDebugLog.log(
                            hypothesisId = "H1",
                            location = "InternetCodeSignaling.connectionLost",
                            message = "connectionLost ignored; awaiting auto-reconnect",
                            data = mapOf(
                                "state" to _state.value.toString(),
                                "cause" to (cause?.message ?: "none")
                            ),
                            runId = "fix-review2"
                        )
                        // #endregion
                    }

                    override fun messageArrived(topic: String?, message: MqttMessage?) {
                        val payload = message?.payload?.toString(Charsets.UTF_8) ?: return
                        scope.launch(signalingDispatcher) { onMessage(payload) }
                    }

                    override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
                })

                val opts = MqttConnectOptions().apply {
                    isAutomaticReconnect = true
                    isCleanSession = true
                    connectionTimeout = 15
                    keepAliveInterval = 30
                    socketFactory = MqttSsl.pinnedSocketFactory()
                }
                mqtt.connect(opts)
            } catch (t: Throwable) {
                if (gen != sessionGen.get()) return@launch
                // #region agent log
                AgentDebugLog.log(
                    hypothesisId = "H4",
                    location = "InternetCodeSignaling.connect",
                    message = "signaling connect failed",
                    data = mapOf("error" to (t.message ?: t.javaClass.simpleName)),
                    runId = "fix-review2"
                )
                // #endregion
                _state.value = PairingSignalState.Failed(t.message ?: "Could not reach signaling")
            }
        }
    }

    private fun reset(keepManifest: Boolean) {
        sessionGen.incrementAndGet()
        connectJob?.cancel()
        connectJob = null
        joinRetryJob?.cancel()
        joinRetryJob = null
        expiryJob?.cancel()
        expiryJob = null
        runCatching { client?.disconnect() }
        runCatching { client?.close() }
        client = null
        topic = null
        role = null
        authKey = ByteArray(0)
        encKey = ByteArray(0)
        sessionExpEpochSec = 0L
        guestLocked = false
        manifestFrozen = false
        seenNonces.clear()
        if (!keepManifest) {
            localManifest = emptyList()
            _remoteFiles.value = emptyList()
        }
        _state.value = PairingSignalState.Idle
    }

    private fun scheduleExpiryWatch() {
        expiryJob?.cancel()
        val exp = sessionExpEpochSec
        expiryJob = scope.launch {
            val delayMs = ((exp * 1000L) - System.currentTimeMillis()).coerceAtLeast(0L) + 250L
            delay(delayMs)
            if (!isActive) return@launch
            if (isSessionExpired() && _state.value !is PairingSignalState.Paired &&
                _state.value !is PairingSignalState.Idle
            ) {
                _state.value = PairingSignalState.Failed("Share code expired")
            }
        }
    }

    private suspend fun onConnected(reconnect: Boolean, gen: Int) {
        if (gen != sessionGen.get()) return
        val mqtt = client ?: return
        val t = topic ?: return
        val r = role ?: return
        if (gen != sessionGen.get()) return
        mqtt.subscribe(t, 1)

        if (reconnect && _state.value is PairingSignalState.Paired) {
            return
        }

        when (r) {
            "host" -> {
                publishSigned("h", "ready")
                publishManifest()
                if (_state.value !is PairingSignalState.Paired) {
                    _state.value = PairingSignalState.Waiting
                }
            }
            "guest" -> {
                publishSigned("g", "join")
                if (_state.value !is PairingSignalState.Paired) {
                    _state.value = PairingSignalState.Waiting
                }
                if (!reconnect || joinRetryJob?.isActive != true) {
                    joinRetryJob?.cancel()
                    joinRetryJob = scope.launch(signalingDispatcher) {
                        // Retry until TTL (or paired/failed), not an arbitrary 30s cutoff.
                        while (isActive &&
                            _state.value !is PairingSignalState.Paired &&
                            _state.value !is PairingSignalState.Failed
                        ) {
                            if (isSessionExpired()) {
                                _state.value = PairingSignalState.Failed("Share code expired")
                                return@launch
                            }
                            delay(JOIN_RETRY_MS)
                            if (_state.value is PairingSignalState.Paired ||
                                _state.value is PairingSignalState.Failed
                            ) return@launch
                            publishSigned("g", "join")
                        }
                    }
                }
            }
        }
    }

    private fun onMessage(payload: String) {
        val r = role ?: return
        if (authKey.isEmpty() || encKey.isEmpty()) return
        val inner = SignalingCrypto.openEnvelope(encKey, payload) ?: run {
            // #region agent log
            AgentDebugLog.log(
                hypothesisId = "H-ENC",
                location = "InternetCodeSignaling.onMessage",
                message = "envelope decrypt failed",
                data = mapOf("role" to r),
                runId = "fix-review2"
            )
            // #endregion
            return
        }
        val obj = runCatching { JSONObject(inner) }.getOrNull() ?: return
        val event = obj.optString("e")
        val from = obj.optString("r")
        val ts = obj.optLong("ts", 0L)
        val exp = obj.optLong("exp", 0L)
        val nonce = obj.optString("nonce")
        val mac = obj.optString("mac")
        val extra = when (event) {
            "manifest" -> obj.optJSONArray("files")?.toString() ?: ""
            else -> ""
        }
        val canonical = SignalingCrypto.canonical(from, event, ts, exp, nonce, extra)
        val macOk = SignalingCrypto.verifyMac(authKey, canonical, mac)
        val now = System.currentTimeMillis() / 1000L
        val fresh = ts in (now - 120)..(now + 60) && exp >= now
        if (!macOk || !fresh) return
        if (nonce.isNotBlank()) {
            synchronized(seenNonces) {
                if (seenNonces.contains(nonce)) return
                seenNonces.addLast(nonce)
                while (seenNonces.size > MAX_SEEN_NONCES) seenNonces.removeFirst()
            }
        }
        if (isSessionExpired()) {
            _state.value = PairingSignalState.Failed("Share code expired")
            return
        }

        // #region agent log
        AgentDebugLog.log(
            hypothesisId = "H3",
            location = "InternetCodeSignaling.onMessage",
            message = "signal message",
            data = mapOf(
                "role" to r,
                "from" to from,
                "event" to event,
                "guestLocked" to guestLocked,
                "manifestFrozen" to manifestFrozen
            ),
            runId = "fix-review2"
        )
        // #endregion

        when {
            event == "manifest" && from == "h" && r == "guest" -> {
                if (manifestFrozen) return
                _remoteFiles.value = parseManifest(obj)
                if (_state.value is PairingSignalState.Paired) {
                    manifestFrozen = true
                }
            }
            r == "host" && from == "g" && event == "join" -> {
                if (guestLocked) {
                    // #region agent log
                    AgentDebugLog.log(
                        hypothesisId = "H3",
                        location = "InternetCodeSignaling.onMessage",
                        message = "rejected extra join (single guest)",
                        data = mapOf("guestLocked" to true),
                        runId = "fix-review2"
                    )
                    // #endregion
                    return
                }
                guestLocked = true
                publishSigned("h", "paired")
                publishManifest()
                markPaired()
            }
            r == "guest" && from == "h" && event == "paired" -> {
                markPaired()
                if (_remoteFiles.value.isNotEmpty()) {
                    manifestFrozen = true
                } else {
                    publishSigned("g", "join")
                }
            }
            r == "guest" && from == "h" && event == "ready" -> {
                publishSigned("g", "join")
            }
        }
    }

    private fun parseManifest(obj: JSONObject): List<SharedFileInfo> {
        val files = obj.optJSONArray("files") ?: return emptyList()
        return buildList {
            for (i in 0 until files.length()) {
                val f = files.optJSONObject(i) ?: continue
                val name = sanitizeWirePath(f.optString("n")) ?: continue
                add(SharedFileInfo(name = name, sizeBytes = f.optLong("s", -1L)))
            }
        }
    }

    private fun publishManifest() {
        if (localManifest.isEmpty()) return
        val files = JSONArray()
        localManifest.forEach { item ->
            val path = sanitizeWirePath(item.name) ?: return@forEach
            files.put(JSONObject().put("n", path.take(180)).put("s", item.sizeBytes))
        }
        val filesStr = files.toString()
        val ts = System.currentTimeMillis() / 1000L
        val exp = sessionExpEpochSec
        val nonce = SignalingCrypto.randomNonce()
        val mac = SignalingCrypto.macHex(
            authKey,
            SignalingCrypto.canonical("h", "manifest", ts, exp, nonce, filesStr)
        )
        val inner = JSONObject()
            .put("r", "h")
            .put("e", "manifest")
            .put("ts", ts)
            .put("exp", exp)
            .put("nonce", nonce)
            .put("mac", mac)
            .put("files", files)
            .toString()
        publishSealed(inner)
    }

    private fun publishSigned(roleChar: String, event: String) {
        val ts = System.currentTimeMillis() / 1000L
        val exp = sessionExpEpochSec
        val nonce = SignalingCrypto.randomNonce()
        val mac = SignalingCrypto.macHex(
            authKey,
            SignalingCrypto.canonical(roleChar, event, ts, exp, nonce)
        )
        val inner = JSONObject()
            .put("r", roleChar)
            .put("e", event)
            .put("ts", ts)
            .put("exp", exp)
            .put("nonce", nonce)
            .put("mac", mac)
            .toString()
        publishSealed(inner)
    }

    private fun publishSealed(innerJson: String) {
        if (encKey.isEmpty()) return
        val envelope = SignalingCrypto.sealEnvelope(encKey, innerJson)
        publish(envelope)
    }

    private fun markPaired() {
        joinRetryJob?.cancel()
        expiryJob?.cancel()
        _state.value = PairingSignalState.Paired
    }

    private fun isSessionExpired(): Boolean {
        if (sessionExpEpochSec <= 0L) return true
        return System.currentTimeMillis() / 1000L > sessionExpEpochSec
    }

    private fun publish(payload: String) {
        val mqtt = client ?: return
        val t = topic ?: return
        if (!mqtt.isConnected) return
        runCatching {
            mqtt.publish(t, MqttMessage(payload.toByteArray(Charsets.UTF_8)).apply { qos = 1 })
        }
    }

    companion object {
        private const val BROKER_URI = "ssl://broker.emqx.io:8883"
        private const val SESSION_TTL_SEC = 10 * 60L
        private const val JOIN_RETRY_MS = 2_000L
        private const val MAX_SEEN_NONCES = 64
        const val MAX_MANIFEST_FILES = 200

        /** Relative POSIX-ish path only; reject traversal / absolute. */
        fun sanitizeWirePath(raw: String): String? {
            val trimmed = raw.trim().replace('\\', '/')
            if (trimmed.isEmpty() || trimmed.length > 180) return null
            if (trimmed.startsWith("/") || trimmed.contains("://")) return null
            val parts = trimmed.split('/').filter { it.isNotEmpty() }
            if (parts.isEmpty()) return null
            if (parts.any { it == "." || it == ".." }) return null
            return parts.joinToString("/")
        }
    }
}
