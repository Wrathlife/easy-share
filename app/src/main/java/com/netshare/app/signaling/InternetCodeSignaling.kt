package com.netshare.app.signaling

import android.content.Context
import com.netshare.app.debug.AgentDebugLog
import com.netshare.app.files.LocalShareEntry
import com.netshare.app.service.ShareForegroundService
import com.netshare.app.webrtc.DataChannelFileTransfer
import com.netshare.app.webrtc.LocalIceCandidate
import com.netshare.app.webrtc.PairingCode
import com.netshare.app.webrtc.WebRtcPeerSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
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
    /** Handshake done — both users must confirm matching phrase. */
    data class Confirming(
        val phrase: String,
        val localConfirmed: Boolean,
        val peerConfirmed: Boolean
    ) : PairingSignalState
    data object Paired : PairingSignalState
    data class Failed(val reason: String) : PairingSignalState
}

private enum class ByteTransferMode { Undecided, WebRtc, Mqtt }

/**
 * Internet pairing + encrypted signaling over MQTTS.
 * File bytes: WebRTC DataChannel by default, MQTT AES path when encrypt is on
 * or when WebRTC ICE/DataChannel fails within the budget.
 */
class InternetCodeSignaling(
    private val scope: CoroutineScope
) {
    private val _state = MutableStateFlow<PairingSignalState>(PairingSignalState.Idle)
    val state: StateFlow<PairingSignalState> = _state.asStateFlow()

    private val _remoteFiles = MutableStateFlow<List<SharedFileInfo>>(emptyList())
    val remoteFiles: StateFlow<List<SharedFileInfo>> = _remoteFiles.asStateFlow()

    private val _transferProgress = MutableStateFlow<com.netshare.app.ui.state.TransferProgressUi?>(null)
    val transferProgress: StateFlow<com.netshare.app.ui.state.TransferProgressUi?> = _transferProgress.asStateFlow()
    private val _savedFiles = MutableStateFlow<List<com.netshare.app.history.ReceivedFileRecord>>(emptyList())
    val savedFiles: StateFlow<List<com.netshare.app.history.ReceivedFileRecord>> = _savedFiles.asStateFlow()
    private val _transferComplete = MutableStateFlow(false)
    val transferComplete: StateFlow<Boolean> = _transferComplete.asStateFlow()
    private val _transferFailed = MutableStateFlow<String?>(null)
    val transferFailed: StateFlow<String?> = _transferFailed.asStateFlow()

    private var fileTransfer: MqttEncryptedFileTransfer? = null
    private var webrtcSession: WebRtcPeerSession? = null
    private var dcTransfer: DataChannelFileTransfer? = null
    private var transferOrchestratorJob: Job? = null
    private var icePublishJob: Job? = null
    private var fgsContext: Context? = null
    private var encryptFileTransfer: Boolean = false
    private var byteMode: ByteTransferMode = ByteTransferMode.Undecided
    private var pendingRemoteOffer: String? = null
    private var pendingRemoteAnswer: String? = null
    private val pendingRemoteIce = mutableListOf<LocalIceCandidate>()
    private val remoteIceDone = MutableStateFlow(false)
    /** Host announced MQTT byte path (encrypt mode or WebRTC fallback). */
    private val forceMqttRelay = MutableStateFlow(false)

    private var client: MqttClient? = null
    private var topic: String? = null
    private var role: String? = null
    private var normalizedCode: String? = null
    private var authKey: ByteArray = ByteArray(0)
    private var encKey: ByteArray = ByteArray(0)
    private var sessionExpEpochSec: Long = 0L
    private var guestLocked: Boolean = false
    private var manifestFrozen: Boolean = false
    private var localConfirmed: Boolean = false
    private var peerConfirmed: Boolean = false
    private var joinRetryJob: Job? = null
    private var connectJob: Job? = null
    private var reconnectJob: Job? = null
    private var expiryJob: Job? = null
    private var localManifest: List<SharedFileInfo> = emptyList()
    private val sessionGen = AtomicInteger(0)
    private val seenNonces = ArrayDeque<String>()
    /** True while the MQTT client reports connected (updated from callbacks). */
    private val mqttConnected = MutableStateFlow(false)
    /** Serializes connect + message handling so guestLocked / manifestFrozen stay consistent. */
    private val signalingDispatcher = Dispatchers.IO.limitedParallelism(1)

    fun startHost(code: String, files: List<SharedFileInfo>) {
        localManifest = files.take(MAX_MANIFEST_FILES)
        AgentDebugLog.log(
            hypothesisId = "H1",
            location = "InternetCodeSignaling.startHost",
            message = "host manifest sized",
            data = mapOf(
                "requested" to files.size,
                "published" to localManifest.size,
                "capped" to (files.size > MAX_MANIFEST_FILES)
            ),
            runId = "post-fix2"
        )
        start(code = code, role = "host")
    }

    fun startGuest(code: String) {
        localManifest = emptyList()
        _remoteFiles.value = emptyList()
        start(code = code, role = "guest")
    }

    /** User tapped “Yes, same phrase” on this device. */
    fun confirmLocalPairing() {
        scope.launch(signalingDispatcher) {
            if (_state.value !is PairingSignalState.Confirming) return@launch
            if (localConfirmed) return@launch
            localConfirmed = true
            val roleChar = if (role == "host") "h" else "g"
            publishSigned(roleChar, "confirm")
            refreshConfirmingState()
            maybeCompletePair()
        }
    }

    fun rejectLocalPairing() {
        scope.launch(signalingDispatcher) {
            val keepHostFiles = role == "host"
            reset(keepManifest = keepHostFiles)
            _state.value = PairingSignalState.Failed("Pairing cancelled — devices did not match")
        }
    }

    /**
     * Host: after both confirm, start transfer.
     * Encrypt ON → MQTT only. Else try WebRTC (~18s) then MQTT fallback.
     */
    fun startHostFileTransfer(
        context: Context,
        entries: List<LocalShareEntry>,
        encryptFileTransfer: Boolean = false
    ) {
        this.encryptFileTransfer = encryptFileTransfer
        val app = context.applicationContext
        fgsContext = app
        ShareForegroundService.start(app, sending = true)
        transferOrchestratorJob?.cancel()
        transferOrchestratorJob = scope.launch(Dispatchers.IO) {
            try {
                if (encryptFileTransfer) {
                    byteMode = ByteTransferMode.Mqtt
                    publishSigned("h", "xfer-mqtt")
                    runMqttHostSend(app, entries)
                } else {
                    val webrtcOk = runCatching { tryHostWebRtc(app, entries) }.getOrDefault(false)
                    if (webrtcOk) {
                        byteMode = ByteTransferMode.WebRtc
                        // DC transfer already started inside tryHostWebRtc
                        awaitTransferTerminal()
                    } else {
                        AgentDebugLog.log(
                            hypothesisId = "H-WEBRTC",
                            location = "InternetCodeSignaling.startHostFileTransfer",
                            message = "WebRTC failed; falling back to MQTT",
                            data = emptyMap(),
                            runId = "webrtc"
                        )
                        closeWebRtc()
                        byteMode = ByteTransferMode.Mqtt
                        publishSigned("h", "xfer-mqtt")
                        runMqttHostSend(app, entries)
                    }
                }
            } catch (t: Throwable) {
                _transferFailed.value = t.message ?: "Transfer failed"
            } finally {
                stopForegroundService()
            }
        }
    }

    /**
     * Guest: prepare on-disk MQTT/WebRTC sink. Pass [beginTransfer]=true only after Paired
     * so the WebRTC budget is not burned during the confirm phrase UI.
     */
    fun prepareGuestFileSink(
        context: Context,
        expected: List<SharedFileInfo>,
        encryptFileTransfer: Boolean = false,
        beginTransfer: Boolean = false
    ) {
        this.encryptFileTransfer = encryptFileTransfer
        val app = context.applicationContext
        fgsContext = app
        ensureMqttFileTransfer()
        val sessionId = normalizedCode?.let { SignalingCrypto.topicId(it) } ?: return
        fileTransfer?.prepareGuestSink(app, sessionId, expected)
        if (!beginTransfer) return

        ShareForegroundService.start(app, sending = false)
        if (transferOrchestratorJob?.isActive == true) return

        transferOrchestratorJob = scope.launch(Dispatchers.IO) {
            try {
                if (encryptFileTransfer || forceMqttRelay.value) {
                    byteMode = ByteTransferMode.Mqtt
                    awaitTransferTerminal()
                } else {
                    val webrtcOk = runCatching { tryGuestWebRtc(app, expected) }.getOrDefault(false)
                    if (webrtcOk) {
                        byteMode = ByteTransferMode.WebRtc
                        awaitTransferTerminal()
                    } else {
                        closeWebRtc()
                        byteMode = ByteTransferMode.Mqtt
                        AgentDebugLog.log(
                            hypothesisId = "H-WEBRTC",
                            location = "InternetCodeSignaling.prepareGuestFileSink",
                            message = "guest WebRTC not ready; waiting for MQTT",
                            data = mapOf("forceMqtt" to forceMqttRelay.value),
                            runId = "webrtc"
                        )
                        awaitTransferTerminal()
                    }
                }
            } catch (t: Throwable) {
                if (_transferFailed.value == null && !_transferComplete.value) {
                    _transferFailed.value = t.message ?: "Transfer failed"
                }
            } finally {
                stopForegroundService()
            }
        }
    }

    fun stop() {
        reset(keepManifest = false)
    }

    private suspend fun runMqttHostSend(context: Context, entries: List<LocalShareEntry>) {
        ensureMqttFileTransfer()
        fileTransfer?.startHostSend(context, entries)
        awaitTransferTerminal()
    }

    private suspend fun awaitTransferTerminal() {
        val finished = withTimeoutOrNull(TRANSFER_OVERALL_TIMEOUT_MS) {
            while (isActive) {
                if (_transferComplete.value) return@withTimeoutOrNull true
                val fail = _transferFailed.value
                if (fail != null) return@withTimeoutOrNull true
                delay(200)
            }
            false
        }
        if (finished != true && !_transferComplete.value && _transferFailed.value == null) {
            if (_savedFiles.value.any { it.downloaded }) {
                _transferComplete.value = true
            } else {
                _transferFailed.value = "Transfer timed out"
            }
        }
    }

    private suspend fun tryHostWebRtc(context: Context, entries: List<LocalShareEntry>): Boolean {
        closeWebRtc()
        pendingRemoteAnswer = null
        remoteIceDone.value = false
        forceMqttRelay.value = false
        val deadline = System.currentTimeMillis() + WEBRTC_BUDGET_MS
        val session = WebRtcPeerSession(context, isHost = true)
        webrtcSession = session
        wireLocalIcePublisher(session, roleChar = "h")

        val offer = session.createOfferSdp()
        publishSdp("h", "sdp-offer", offer)

        val remainForAnswer = (deadline - System.currentTimeMillis()).coerceAtLeast(1L)
        val answer = withTimeoutOrNull(remainForAnswer) {
            while (isActive) {
                if (forceMqttRelay.value) return@withTimeoutOrNull null
                pendingRemoteAnswer?.let { return@withTimeoutOrNull it }
                delay(50)
            }
            null
        } ?: return false

        session.applyRemoteAnswer(answer)
        flushPendingRemoteIce(session)

        val remainForDc = (deadline - System.currentTimeMillis()).coerceAtLeast(1L)
        if (!session.awaitDataChannelOpen(remainForDc)) return false

        byteMode = ByteTransferMode.WebRtc
        val dc = DataChannelFileTransfer(
            scope = scope,
            session = session,
            progressOut = _transferProgress,
            savedOut = _savedFiles,
            completeOut = _transferComplete,
            failedOut = _transferFailed
        )
        dcTransfer = dc
        dc.startHostSend(context, entries)
        return true
    }

    private suspend fun tryGuestWebRtc(context: Context, expected: List<SharedFileInfo>): Boolean {
        closeWebRtc()
        remoteIceDone.value = false
        val deadline = System.currentTimeMillis() + WEBRTC_BUDGET_MS
        val session = WebRtcPeerSession(context, isHost = false)
        webrtcSession = session
        wireLocalIcePublisher(session, roleChar = "g")

        val sessionId = normalizedCode?.let { SignalingCrypto.topicId(it) } ?: return false
        val dc = DataChannelFileTransfer(
            scope = scope,
            session = session,
            progressOut = _transferProgress,
            savedOut = _savedFiles,
            completeOut = _transferComplete,
            failedOut = _transferFailed
        )
        dcTransfer = dc
        dc.prepareGuestSink(context, sessionId, expected)
        // Start collecting BEFORE the channel opens so early frames are not lost.
        dc.startGuestReceive()

        val remainForOffer = (deadline - System.currentTimeMillis()).coerceAtLeast(1L)
        val offer = withTimeoutOrNull(remainForOffer) {
            pendingRemoteOffer?.let { return@withTimeoutOrNull it }
            while (isActive) {
                if (forceMqttRelay.value) return@withTimeoutOrNull null
                pendingRemoteOffer?.let { return@withTimeoutOrNull it }
                delay(50)
            }
            null
        } ?: return false

        val answer = session.applyRemoteOfferAndCreateAnswer(offer)
        publishSdp("g", "sdp-answer", answer)
        flushPendingRemoteIce(session)

        val remainForDc = (deadline - System.currentTimeMillis()).coerceAtLeast(1L)
        val opened = withTimeoutOrNull(remainForDc) {
            while (isActive) {
                if (forceMqttRelay.value) return@withTimeoutOrNull false
                if (session.awaitDataChannelOpen(200L)) return@withTimeoutOrNull true
            }
            false
        } == true
        if (!opened) return false

        byteMode = ByteTransferMode.WebRtc
        return true
    }

    private fun wireLocalIcePublisher(session: WebRtcPeerSession, roleChar: String) {
        icePublishJob?.cancel()
        icePublishJob = scope.launch(Dispatchers.IO) {
            launch {
                session.localIce.collect { ice ->
                    publishIce(roleChar, ice)
                }
            }
            session.iceGatheringComplete.first { it }
            publishSigned(roleChar, "ice-done")
        }
    }

    private fun flushPendingRemoteIce(session: WebRtcPeerSession) {
        val copy: List<LocalIceCandidate>
        synchronized(pendingRemoteIce) {
            copy = pendingRemoteIce.toList()
            pendingRemoteIce.clear()
        }
        copy.forEach { session.addRemoteIce(it.sdpMid, it.sdpMLineIndex, it.sdp) }
    }

    private fun ensureMqttFileTransfer() {
        if (fileTransfer != null) return
        fileTransfer = MqttEncryptedFileTransfer(
            scope = scope,
            publishSealed = { json, qos -> publishSealed(json, qos) },
            authKey = { authKey },
            sessionExp = { sessionExpEpochSec },
            isLive = { role != null && authKey.isNotEmpty() },
            awaitConnected = { timeoutMs -> awaitMqttConnected(timeoutMs) },
            progressOut = _transferProgress,
            savedOut = _savedFiles,
            completeOut = _transferComplete,
            failedOut = _transferFailed
        )
    }

    private fun closeWebRtc() {
        icePublishJob?.cancel()
        icePublishJob = null
        dcTransfer?.reset()
        dcTransfer = null
        webrtcSession?.close()
        webrtcSession = null
    }

    private fun stopForegroundService() {
        fgsContext?.let { ShareForegroundService.stop(it) }
    }

    private fun start(code: String, role: String) {
        reset(keepManifest = true)
        val normalized = PairingCode.normalize(code)
        if (!PairingCode.isValidShort(normalized)) {
            _state.value = PairingSignalState.Failed("Invalid share code format")
            return
        }

        this.role = role
        this.normalizedCode = normalized
        authKey = ByteArray(0)
        encKey = ByteArray(0)
        sessionExpEpochSec = (System.currentTimeMillis() / 1000L) + SESSION_TTL_SEC
        guestLocked = false
        manifestFrozen = false
        localConfirmed = false
        peerConfirmed = false
        seenNonces.clear()
        topic = "easyshare/v1/${SignalingCrypto.topicId(normalized)}"
        _state.value = PairingSignalState.Connecting
        scheduleExpiryWatch()

        val gen = sessionGen.incrementAndGet()
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
            runId = "post-fix2"
        )

        connectJob?.cancel()
        connectJob = scope.launch(Dispatchers.IO) {
            try {
                val keys = SignalingCrypto.sessionKeys(normalized)
                if (gen != sessionGen.get()) return@launch
                authKey = keys.auth
                encKey = keys.enc

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
                        mqttConnected.value = true
                        scope.launch(signalingDispatcher) {
                            if (gen != sessionGen.get()) return@launch
                            onConnected(reconnect = reconnect, gen = gen)
                        }
                    }

                    override fun connectionLost(cause: Throwable?) {
                        mqttConnected.value = false
                        AgentDebugLog.log(
                            hypothesisId = "H1",
                            location = "InternetCodeSignaling.connectionLost",
                            message = "mqtt connection lost; scheduling reconnect",
                            data = mapOf(
                                "state" to _state.value.toString(),
                                "cause" to (cause?.message ?: "none")
                            ),
                            runId = "reconnect"
                        )
                        fileTransfer?.onBrokerDisconnected()
                        scheduleReconnect(gen)
                    }

                    override fun messageArrived(topic: String?, message: MqttMessage?) {
                        val payload = message?.payload?.toString(Charsets.UTF_8) ?: return
                        scope.launch(signalingDispatcher) { onMessage(payload) }
                    }

                    override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
                })

                val opts = buildConnectOptions()
                mqtt.connect(opts)
                mqttConnected.value = mqtt.isConnected
            } catch (t: Throwable) {
                if (gen != sessionGen.get()) return@launch
                val detail = formatThrowable(t)
                AgentDebugLog.log(
                    hypothesisId = "H4",
                    location = "InternetCodeSignaling.connect",
                    message = "signaling connect failed",
                    data = mapOf("error" to detail),
                    runId = "post-fix2"
                )
                _state.value = PairingSignalState.Failed(detail)
            }
        }
    }

    private fun reset(keepManifest: Boolean) {
        sessionGen.incrementAndGet()
        connectJob?.cancel()
        connectJob = null
        reconnectJob?.cancel()
        reconnectJob = null
        joinRetryJob?.cancel()
        joinRetryJob = null
        expiryJob?.cancel()
        expiryJob = null
        transferOrchestratorJob?.cancel()
        transferOrchestratorJob = null
        closeWebRtc()
        stopForegroundService()
        fgsContext = null
        fileTransfer?.reset()
        fileTransfer = null
        _transferProgress.value = null
        _savedFiles.value = emptyList()
        _transferComplete.value = false
        _transferFailed.value = null
        encryptFileTransfer = false
        byteMode = ByteTransferMode.Undecided
        pendingRemoteOffer = null
        pendingRemoteAnswer = null
        synchronized(pendingRemoteIce) { pendingRemoteIce.clear() }
        remoteIceDone.value = false
        forceMqttRelay.value = false
        mqttConnected.value = false
        val old = client
        client = null
        if (old != null) {
            scope.launch(Dispatchers.IO) {
                runCatching { old.disconnect(1_000) }
                runCatching { old.close() }
            }
        }
        topic = null
        role = null
        normalizedCode = null
        authKey = ByteArray(0)
        encKey = ByteArray(0)
        sessionExpEpochSec = 0L
        guestLocked = false
        manifestFrozen = false
        localConfirmed = false
        peerConfirmed = false
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
            if (isSessionExpired() &&
                _state.value !is PairingSignalState.Paired &&
                _state.value !is PairingSignalState.Confirming &&
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

        if (reconnect &&
            (_state.value is PairingSignalState.Paired || _state.value is PairingSignalState.Confirming)
        ) {
            return
        }

        when (r) {
            "host" -> {
                publishSigned("h", "ready")
                publishManifest()
                if (_state.value !is PairingSignalState.Paired &&
                    _state.value !is PairingSignalState.Confirming
                ) {
                    _state.value = PairingSignalState.Waiting
                }
            }
            "guest" -> {
                publishSigned("g", "join")
                if (_state.value !is PairingSignalState.Paired &&
                    _state.value !is PairingSignalState.Confirming
                ) {
                    _state.value = PairingSignalState.Waiting
                }
                if (!reconnect || joinRetryJob?.isActive != true) {
                    joinRetryJob?.cancel()
                    joinRetryJob = scope.launch(signalingDispatcher) {
                        while (isActive &&
                            _state.value !is PairingSignalState.Paired &&
                            _state.value !is PairingSignalState.Confirming &&
                            _state.value !is PairingSignalState.Failed
                        ) {
                            if (isSessionExpired()) {
                                _state.value = PairingSignalState.Failed("Share code expired")
                                return@launch
                            }
                            delay(JOIN_RETRY_MS)
                            if (_state.value is PairingSignalState.Paired ||
                                _state.value is PairingSignalState.Confirming ||
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
            AgentDebugLog.log(
                hypothesisId = "H-ENC",
                location = "InternetCodeSignaling.onMessage",
                message = "envelope decrypt failed",
                data = mapOf("role" to r),
                runId = "post-fix2"
            )
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
            "fstart", "fbin", "fdone" -> MqttEncryptedFileTransfer.transferExtra(obj)
            "sdp-offer", "sdp-answer" -> shortHash(obj.optString("sdp"))
            "ice" -> shortHash(obj.optString("candidate"))
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
            runId = "post-fix2"
        )

        when {
            event == "manifest" && from == "h" && r == "guest" -> {
                if (manifestFrozen) return
                _remoteFiles.value = parseManifest(obj)
                if (_state.value is PairingSignalState.Paired ||
                    _state.value is PairingSignalState.Confirming
                ) {
                    manifestFrozen = true
                }
            }
            r == "host" && from == "g" && event == "join" -> {
                if (guestLocked) {
                    AgentDebugLog.log(
                        hypothesisId = "H3",
                        location = "InternetCodeSignaling.onMessage",
                        message = "rejected extra join (single guest)",
                        data = mapOf("guestLocked" to true),
                        runId = "post-fix2"
                    )
                    return
                }
                guestLocked = true
                publishSigned("h", "paired")
                publishManifest()
                enterConfirming()
            }
            r == "guest" && from == "h" && event == "paired" -> {
                joinRetryJob?.cancel()
                if (_remoteFiles.value.isNotEmpty()) {
                    manifestFrozen = true
                }
                enterConfirming()
            }
            r == "guest" && from == "h" && event == "ready" -> {
                publishSigned("g", "join")
            }
            event == "confirm" &&
                ((r == "host" && from == "g") || (r == "guest" && from == "h")) -> {
                peerConfirmed = true
                refreshConfirmingState()
                maybeCompletePair()
            }
            event == "sdp-offer" && r == "guest" && from == "h" -> {
                pendingRemoteOffer = obj.optString("sdp")
            }
            event == "sdp-answer" && r == "host" && from == "g" -> {
                pendingRemoteAnswer = obj.optString("sdp")
            }
            event == "ice" &&
                ((r == "host" && from == "g") || (r == "guest" && from == "h")) -> {
                val cand = LocalIceCandidate(
                    sdpMid = obj.optString("sdpMid").ifBlank { null },
                    sdpMLineIndex = obj.optInt("sdpMLineIndex", 0),
                    sdp = obj.optString("candidate")
                )
                if (cand.sdp.isBlank()) return
                val session = webrtcSession
                if (session != null) {
                    session.addRemoteIce(cand.sdpMid, cand.sdpMLineIndex, cand.sdp)
                } else {
                    synchronized(pendingRemoteIce) { pendingRemoteIce += cand }
                }
            }
            event == "ice-done" &&
                ((r == "host" && from == "g") || (r == "guest" && from == "h")) -> {
                remoteIceDone.value = true
            }
            event == "xfer-mqtt" && from == "h" && r == "guest" -> {
                forceMqttRelay.value = true
                if (!_transferComplete.value) {
                    closeWebRtc()
                    byteMode = ByteTransferMode.Mqtt
                }
            }
            r == "guest" && from == "h" &&
                event in setOf("fstart", "fbin", "fdone", "xfer-complete") -> {
                if (byteMode == ByteTransferMode.WebRtc) return
                if (byteMode == ByteTransferMode.Undecided) {
                    byteMode = ByteTransferMode.Mqtt
                    closeWebRtc()
                }
                ensureMqttFileTransfer()
                fileTransfer?.onGuestEvent(event, obj)
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

    private fun publishSdp(roleChar: String, event: String, sdp: String) {
        val ts = System.currentTimeMillis() / 1000L
        val exp = sessionExpEpochSec
        val nonce = SignalingCrypto.randomNonce()
        val extra = shortHash(sdp)
        val mac = SignalingCrypto.macHex(
            authKey,
            SignalingCrypto.canonical(roleChar, event, ts, exp, nonce, extra)
        )
        val inner = JSONObject()
            .put("r", roleChar)
            .put("e", event)
            .put("ts", ts)
            .put("exp", exp)
            .put("nonce", nonce)
            .put("mac", mac)
            .put("sdp", sdp)
            .toString()
        publishSealed(inner, qos = 1)
    }

    private fun publishIce(roleChar: String, ice: LocalIceCandidate) {
        val ts = System.currentTimeMillis() / 1000L
        val exp = sessionExpEpochSec
        val nonce = SignalingCrypto.randomNonce()
        val extra = shortHash(ice.sdp)
        val mac = SignalingCrypto.macHex(
            authKey,
            SignalingCrypto.canonical(roleChar, "ice", ts, exp, nonce, extra)
        )
        val inner = JSONObject()
            .put("r", roleChar)
            .put("e", "ice")
            .put("ts", ts)
            .put("exp", exp)
            .put("nonce", nonce)
            .put("mac", mac)
            .put("candidate", ice.sdp)
            .put("sdpMid", ice.sdpMid ?: "")
            .put("sdpMLineIndex", ice.sdpMLineIndex)
            .toString()
        publishSealed(inner, qos = 1)
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

    private fun publishSealed(innerJson: String, qos: Int = 1) {
        if (encKey.isEmpty()) return
        val envelope = SignalingCrypto.sealEnvelope(encKey, innerJson)
        publish(envelope, qos)
    }

    private fun enterConfirming() {
        joinRetryJob?.cancel()
        val code = normalizedCode ?: return
        val phrase = SignalingCrypto.confirmPhrase(code)
        _state.value = PairingSignalState.Confirming(
            phrase = phrase,
            localConfirmed = localConfirmed,
            peerConfirmed = peerConfirmed
        )
    }

    private fun refreshConfirmingState() {
        val current = _state.value
        if (current !is PairingSignalState.Confirming) return
        _state.value = current.copy(
            localConfirmed = localConfirmed,
            peerConfirmed = peerConfirmed
        )
    }

    private fun maybeCompletePair() {
        if (localConfirmed && peerConfirmed) {
            markPaired()
        }
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

    private fun buildConnectOptions(): MqttConnectOptions =
        MqttConnectOptions().apply {
            isAutomaticReconnect = false
            isCleanSession = true
            connectionTimeout = 20
            keepAliveInterval = 20
            mqttVersion = MqttConnectOptions.MQTT_VERSION_3_1_1
            socketFactory = MqttSsl.pinnedSocketFactory()
        }

    private fun scheduleReconnect(gen: Int) {
        if (gen != sessionGen.get()) return
        when (_state.value) {
            is PairingSignalState.Idle, is PairingSignalState.Failed -> return
            else -> Unit
        }
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch(Dispatchers.IO) {
            val deadline = System.currentTimeMillis() + RECONNECT_BUDGET_MS
            var waitMs = RECONNECT_INITIAL_MS
            AgentDebugLog.log(
                hypothesisId = "H1",
                location = "InternetCodeSignaling.scheduleReconnect",
                message = "reconnect budget started",
                data = mapOf("budgetMs" to RECONNECT_BUDGET_MS),
                runId = "reconnect"
            )
            while (isActive && gen == sessionGen.get() && System.currentTimeMillis() < deadline) {
                when (_state.value) {
                    is PairingSignalState.Idle, is PairingSignalState.Failed -> return@launch
                    else -> Unit
                }
                val mqtt = client ?: return@launch
                if (mqtt.isConnected) {
                    mqttConnected.value = true
                    return@launch
                }
                val connected = runCatching {
                    mqtt.connect(buildConnectOptions())
                    mqtt.isConnected
                }.getOrDefault(false)
                if (connected) {
                    mqttConnected.value = true
                    return@launch
                }
                delay(waitMs)
                waitMs = (waitMs * 2).coerceAtMost(RECONNECT_MAX_BACKOFF_MS)
            }
            if (gen != sessionGen.get()) return@launch
            if (client?.isConnected == true) return@launch
            _transferFailed.value = "Connection lost"
            _state.value = PairingSignalState.Failed(
                "Connection lost — check the network and try again"
            )
        }
    }

    private suspend fun awaitMqttConnected(timeoutMs: Long): Boolean {
        if (client?.isConnected == true) {
            mqttConnected.value = true
            return true
        }
        scheduleReconnect(sessionGen.get())
        val ok = withTimeoutOrNull(timeoutMs) {
            mqttConnected.first { it }
        } != null
        return ok && client?.isConnected == true
    }

    private fun publish(payload: String, qos: Int = 1) {
        val mqtt = client ?: return
        val t = topic ?: return
        if (!mqtt.isConnected) return
        runCatching {
            mqtt.publish(
                t,
                MqttMessage(payload.toByteArray(Charsets.UTF_8)).apply {
                    this.qos = qos.coerceIn(0, 1)
                }
            )
        }
    }

    private fun formatThrowable(t: Throwable): String {
        val parts = ArrayList<String>()
        var cur: Throwable? = t
        var depth = 0
        while (cur != null && depth < 5) {
            if (cur is org.eclipse.paho.client.mqttv3.MqttException) {
                parts += "MQTT ${cur.reasonCode}"
            }
            val msg = cur.message?.trim().orEmpty()
            if (msg.isNotEmpty() && msg != "MqttException") {
                parts += msg
            } else if (parts.isEmpty()) {
                parts += cur.javaClass.simpleName
            }
            cur = cur.cause
            depth++
        }
        return parts.distinct().joinToString(" — ").ifBlank { "Could not reach signaling" }
    }

    companion object {
        private const val BROKER_URI = "ssl://broker.emqx.io:8883"
        private const val SESSION_TTL_SEC = 10 * 60L
        private const val JOIN_RETRY_MS = 2_000L
        private const val RECONNECT_BUDGET_MS = 45_000L
        private const val RECONNECT_INITIAL_MS = 1_000L
        private const val RECONNECT_MAX_BACKOFF_MS = 8_000L
        private const val MAX_SEEN_NONCES = 64
        /** Budget for ICE + DataChannel open before MQTT fallback. */
        private const val WEBRTC_BUDGET_MS = 18_000L
        private const val TRANSFER_OVERALL_TIMEOUT_MS = 30L * 60L * 1000L
        const val MAX_MANIFEST_FILES = 200
        /** @see com.netshare.app.transfer.TransferLimits.MAX_FILE_BYTES */
        const val MAX_FILE_BYTES: Long = com.netshare.app.transfer.TransferLimits.MAX_FILE_BYTES

        fun sanitizeWirePath(raw: String): String? {
            val trimmed = raw.trim().replace('\\', '/')
            if (trimmed.isEmpty() || trimmed.length > 180) return null
            if (trimmed.startsWith("/") || trimmed.contains("://")) return null
            val parts = trimmed.split('/').filter { it.isNotEmpty() }
            if (parts.isEmpty()) return null
            if (parts.any { it == "." || it == ".." }) return null
            return parts.joinToString("/")
        }

        /** Compact MAC extra — hash prefix, not the full SDP/candidate. */
        fun shortHash(value: String): String {
            if (value.isEmpty()) return ""
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }.take(16)
        }
    }
}
