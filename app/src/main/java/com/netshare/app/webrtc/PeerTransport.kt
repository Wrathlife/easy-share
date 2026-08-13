package com.netshare.app.webrtc

/**
 * Thin transport boundary so iOS/Windows can swap implementations later.
 * Android v1 wraps [WebRtcPeerSession]'s DataChannel.
 */
interface PeerTransport {
    suspend fun openDataChannel(label: String = "easyshare")
    suspend fun send(bytes: ByteArray)
    fun close()
}

class WebRtcPeerTransport(
    private val session: WebRtcPeerSession
) : PeerTransport {
    override suspend fun openDataChannel(label: String) {
        if (!session.awaitDataChannelOpen(18_000L)) {
            error("DataChannel did not open")
        }
    }

    override suspend fun send(bytes: ByteArray) {
        if (!session.send(bytes)) error("DataChannel send failed")
    }

    override fun close() = session.close()
}
