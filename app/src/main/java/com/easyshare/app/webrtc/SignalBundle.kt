package com.easyshare.app.webrtc

/**
 * Versioned QR Offer/Answer bundle (platform-neutral).
 * Compression + Base45 encoding comes in the QR codec phase.
 */
data class SignalBundle(
    val version: Int = 1,
    val role: Role,
    val sessionId: ByteArray,
    val auth: ByteArray,
    val strategyId: String,
    val sdp: String,
    val expiresAtEpochSec: Long
) {
    enum class Role { Offer, Answer }
}

object StunConfig {
    val servers: List<String> = listOf(
        "stun:stun.l.google.com:19302",
        "stun:stun1.l.google.com:19302",
        "stun:stun.cloudflare.com:3478"
    )
}
