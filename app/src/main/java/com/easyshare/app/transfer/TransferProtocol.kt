package com.easyshare.app.transfer

/**
 * Platform-neutral share manifest. Paths are relative — never content:// URIs.
 */
data class ShareManifest(
    val files: List<ShareFile>
)

data class ShareFile(
    val relativePath: String,
    val sizeBytes: Long,
    val sha256Hex: String
)

enum class FrameType(val code: Byte) {
    Hello(1),
    Manifest(2),
    ListOk(3),
    Get(4),
    Chunk(5),
    Ack(6),
    Nack(7),
    Done(8),
    Cancel(9)
}

/**
 * Placeholder framing; binary codec lands with the transfer-protocol phase.
 */
object FrameCodec {
    fun encode(type: FrameType, payload: ByteArray): ByteArray =
        byteArrayOf(type.code) + payload

    fun peekType(frame: ByteArray): FrameType? {
        if (frame.isEmpty()) return null
        return FrameType.entries.firstOrNull { it.code == frame[0] }
    }
}

data class ProgressSnapshot(
    val bytesDone: Long,
    val bytesTotal: Long,
    val currentFileDone: Long,
    val currentFileTotal: Long,
    val speedBytesPerSec: Long
)

class ProgressTracker(
    private val emaAlpha: Double = 0.25
) {
    private var lastBytes = 0L
    private var lastAtMs = 0L
    private var emaSpeed = 0.0

    fun onBytes(done: Long, nowMs: Long = System.currentTimeMillis()): Long {
        if (lastAtMs == 0L) {
            lastBytes = done
            lastAtMs = nowMs
            return 0L
        }
        val dt = (nowMs - lastAtMs).coerceAtLeast(1L)
        val instant = (done - lastBytes) * 1000.0 / dt
        emaSpeed = if (emaSpeed == 0.0) instant else emaAlpha * instant + (1 - emaAlpha) * emaSpeed
        lastBytes = done
        lastAtMs = nowMs
        return emaSpeed.toLong().coerceAtLeast(0L)
    }
}
