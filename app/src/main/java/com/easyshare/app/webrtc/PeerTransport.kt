package com.easyshare.app.webrtc

/**
 * Thin transport boundary so iOS/Windows can swap implementations later.
 * Android v1 will wrap WebRTC DataChannel here.
 */
interface PeerTransport {
    suspend fun openDataChannel(label: String = "easyshare")
    suspend fun send(bytes: ByteArray)
    fun close()
}

class NotImplementedPeerTransport : PeerTransport {
    override suspend fun openDataChannel(label: String) {
        error("WebRTC PeerTransport not wired yet")
    }

    override suspend fun send(bytes: ByteArray) {
        error("WebRTC PeerTransport not wired yet")
    }

    override fun close() = Unit
}
