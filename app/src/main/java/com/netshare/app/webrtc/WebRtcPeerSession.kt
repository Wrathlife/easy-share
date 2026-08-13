package com.netshare.app.webrtc

import android.content.Context
import com.netshare.app.debug.AgentDebugLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withTimeoutOrNull
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

data class LocalIceCandidate(
    val sdpMid: String?,
    val sdpMLineIndex: Int,
    val sdp: String
)

/**
 * STUN-only WebRTC peer with a single reliable DataChannel for file bytes.
 * Signaling (SDP/ICE) is handled by the caller over MQTT.
 */
class WebRtcPeerSession(
    context: Context,
    private val isHost: Boolean
) {
    private val appContext = context.applicationContext
    private val closed = AtomicBoolean(false)

    private val localIceChannel = Channel<LocalIceCandidate>(Channel.BUFFERED)
    val localIce: Flow<LocalIceCandidate> = localIceChannel.receiveAsFlow()

    private val _iceGatheringComplete = MutableStateFlow(false)
    val iceGatheringComplete: StateFlow<Boolean> = _iceGatheringComplete.asStateFlow()

    private val _dataChannelOpen = MutableStateFlow(false)
    val dataChannelOpen: StateFlow<Boolean> = _dataChannelOpen.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Unlimited so early frames aren't dropped before the guest collector starts. */
    private val incomingChannel = Channel<ByteArray>(Channel.UNLIMITED)
    val incoming: Flow<ByteArray> = incomingChannel.receiveAsFlow()

    private var peerConnection: PeerConnection? = null
    private var dataChannel: DataChannel? = null
    private val remoteDescriptionSet = AtomicBoolean(false)
    private val pendingRemoteIce = mutableListOf<IceCandidate>()
    private val iceLock = Any()

    private val pcObserver = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState?) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            if (state == PeerConnection.IceConnectionState.FAILED) {
                _error.value = "ICE connection failed"
            }
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
            if (state == PeerConnection.IceGatheringState.COMPLETE) {
                _iceGatheringComplete.value = true
            }
        }

        override fun onIceCandidate(candidate: IceCandidate?) {
            if (candidate == null) return
            localIceChannel.trySend(
                LocalIceCandidate(
                    sdpMid = candidate.sdpMid,
                    sdpMLineIndex = candidate.sdpMLineIndex,
                    sdp = candidate.sdp
                )
            )
        }

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
        override fun onAddStream(stream: MediaStream?) = Unit
        override fun onRemoveStream(stream: MediaStream?) = Unit
        override fun onDataChannel(dc: DataChannel?) {
            if (dc == null) return
            attachDataChannel(dc)
        }

        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: org.webrtc.RtpReceiver?, mediaStreams: Array<out MediaStream>?) = Unit
    }

    init {
        ensureFactory(appContext)
        val factory = factory ?: error("PeerConnectionFactory missing")
        val iceServers = StunConfig.servers.map { uri ->
            PeerConnection.IceServer.builder(uri).createIceServer()
        }
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
        }
        peerConnection = factory.createPeerConnection(rtcConfig, pcObserver)
            ?: error("Could not create PeerConnection")
        if (isHost) {
            val init = DataChannel.Init().apply {
                ordered = true
                negotiated = false
            }
            val dc = peerConnection?.createDataChannel(DC_LABEL, init)
            if (dc == null) {
                _error.value = "Could not create DataChannel"
            } else {
                attachDataChannel(dc)
            }
        }
    }

    suspend fun createOfferSdp(): String {
        val pc = peerConnection ?: error("PeerConnection closed")
        val constraints = MediaConstraints()
        val sdp = createSdp { observer -> pc.createOffer(observer, constraints) }
        setLocalSdp(sdp)
        return sdp.description
    }

    suspend fun applyRemoteOfferAndCreateAnswer(remoteSdp: String): String {
        setRemoteSdp(SessionDescription(SessionDescription.Type.OFFER, remoteSdp))
        val pc = peerConnection ?: error("PeerConnection closed")
        val constraints = MediaConstraints()
        val answer = createSdp { observer -> pc.createAnswer(observer, constraints) }
        setLocalSdp(answer)
        return answer.description
    }

    suspend fun applyRemoteAnswer(remoteSdp: String) {
        setRemoteSdp(SessionDescription(SessionDescription.Type.ANSWER, remoteSdp))
    }

    fun addRemoteIce(sdpMid: String?, sdpMLineIndex: Int, candidate: String) {
        if (closed.get()) return
        val ice = IceCandidate(sdpMid, sdpMLineIndex, candidate)
        synchronized(iceLock) {
            if (!remoteDescriptionSet.get()) {
                pendingRemoteIce += ice
                return
            }
        }
        peerConnection?.addIceCandidate(ice)
    }

    suspend fun awaitDataChannelOpen(timeoutMs: Long): Boolean {
        if (_dataChannelOpen.value) return true
        val err = _error.value
        if (err != null) return false
        return withTimeoutOrNull(timeoutMs) {
            _dataChannelOpen.first { it }
            true
        } == true
    }

    fun send(bytes: ByteArray): Boolean {
        val dc = dataChannel ?: return false
        if (dc.state() != DataChannel.State.OPEN) return false
        val buffer = DataChannel.Buffer(ByteBuffer.wrap(bytes), true)
        return dc.send(buffer)
    }

    /** Block until bufferedAmount drains under [threshold], or [timeoutMs] elapses. */
    suspend fun awaitSendBufferLow(threshold: Long = 256L * 1024L, timeoutMs: Long = 30_000L): Boolean {
        val dc = dataChannel ?: return false
        if (dc.bufferedAmount() <= threshold) return true
        return withTimeoutOrNull(timeoutMs) {
            while (dc.bufferedAmount() > threshold) {
                kotlinx.coroutines.delay(15)
            }
            true
        } == true
    }

    /** Wait until the SCTP send buffer is essentially empty (data on the wire / delivered). */
    suspend fun awaitSendBufferDrained(timeoutMs: Long = 45_000L): Boolean =
        awaitSendBufferLow(threshold = 16L * 1024L, timeoutMs = timeoutMs)


    fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { dataChannel?.unregisterObserver() }
        runCatching { dataChannel?.close() }
        dataChannel = null
        runCatching { peerConnection?.close() }
        runCatching { peerConnection?.dispose() }
        peerConnection = null
        _dataChannelOpen.value = false
        incomingChannel.close()
        localIceChannel.close()
    }

    private fun attachDataChannel(dc: DataChannel) {
        dataChannel = dc
        dc.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) = Unit

            override fun onStateChange() {
                val open = dc.state() == DataChannel.State.OPEN
                _dataChannelOpen.value = open
                AgentDebugLog.log(
                    hypothesisId = "H-WEBRTC",
                    location = "WebRtcPeerSession.dcState",
                    message = "datachannel state",
                    data = mapOf("state" to dc.state().name, "host" to isHost),
                    runId = "webrtc"
                )
            }

            override fun onMessage(buffer: DataChannel.Buffer?) {
                if (buffer == null) return
                val data = buffer.data ?: return
                val bytes = ByteArray(data.remaining())
                data.get(bytes)
                incomingChannel.trySend(bytes)
            }
        })
        if (dc.state() == DataChannel.State.OPEN) {
            _dataChannelOpen.value = true
        }
    }

    private suspend fun createSdp(block: (SdpObserver) -> Unit): SessionDescription {
        val deferred = CompletableDeferred<SessionDescription>()
        block(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {
                if (desc == null) deferred.completeExceptionally(IllegalStateException("null SDP"))
                else deferred.complete(desc)
            }

            override fun onSetSuccess() = Unit
            override fun onCreateFailure(error: String?) {
                deferred.completeExceptionally(IllegalStateException(error ?: "createSdp failed"))
            }

            override fun onSetFailure(error: String?) {
                deferred.completeExceptionally(IllegalStateException(error ?: "setSdp failed"))
            }
        })
        return deferred.await()
    }

    private suspend fun setLocalSdp(sdp: SessionDescription) {
        val pc = peerConnection ?: error("PeerConnection closed")
        val deferred = CompletableDeferred<Unit>()
        pc.setLocalDescription(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) = Unit
            override fun onSetSuccess() {
                deferred.complete(Unit)
            }

            override fun onCreateFailure(error: String?) {
                deferred.completeExceptionally(IllegalStateException(error ?: "setLocal create fail"))
            }

            override fun onSetFailure(error: String?) {
                deferred.completeExceptionally(IllegalStateException(error ?: "setLocal failed"))
            }
        }, sdp)
        deferred.await()
    }

    private suspend fun setRemoteSdp(sdp: SessionDescription) {
        val pc = peerConnection ?: error("PeerConnection closed")
        val deferred = CompletableDeferred<Unit>()
        pc.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) = Unit
            override fun onSetSuccess() {
                deferred.complete(Unit)
            }

            override fun onCreateFailure(error: String?) {
                deferred.completeExceptionally(IllegalStateException(error ?: "setRemote create fail"))
            }

            override fun onSetFailure(error: String?) {
                deferred.completeExceptionally(IllegalStateException(error ?: "setRemote failed"))
            }
        }, sdp)
        deferred.await()
        remoteDescriptionSet.set(true)
        val pending: List<IceCandidate>
        synchronized(iceLock) {
            pending = pendingRemoteIce.toList()
            pendingRemoteIce.clear()
        }
        pending.forEach { pc.addIceCandidate(it) }
    }

    companion object {
        private const val DC_LABEL = "easyshare"
        @Volatile private var initialized = false
        @Volatile private var factory: PeerConnectionFactory? = null
        private val lock = Any()

        fun ensureFactory(context: Context) {
            if (initialized && factory != null) return
            synchronized(lock) {
                if (initialized && factory != null) return
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                        .setEnableInternalTracer(false)
                        .createInitializationOptions()
                )
                factory = PeerConnectionFactory.builder()
                    .setOptions(PeerConnectionFactory.Options())
                    .createPeerConnectionFactory()
                initialized = true
            }
        }
    }
}
