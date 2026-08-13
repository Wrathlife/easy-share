package com.netshare.app.webrtc

import android.content.Context
import android.net.Uri
import com.netshare.app.debug.AgentDebugLog
import com.netshare.app.files.LocalShareEntry
import com.netshare.app.history.ReceivedFileRecord
import com.netshare.app.signaling.InternetCodeSignaling
import com.netshare.app.signaling.SharedFileInfo
import com.netshare.app.transfer.TransferLimits
import com.netshare.app.ui.state.QueueItemStatus
import com.netshare.app.ui.state.TransferProgressUi
import com.netshare.app.ui.state.TransferQueueItemUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * Binary file transfer over an open WebRTC DataChannel.
 * Frames: HELLO / READY / FILE_BEGIN / CHUNK / FILE_DONE / XFER_DONE / XFER_ACK.
 * Guest writes `*.partial` then renames after FILE_DONE.
 */
class DataChannelFileTransfer(
    private val scope: CoroutineScope,
    private val session: WebRtcPeerSession,
    private val progressOut: MutableStateFlow<TransferProgressUi?>,
    private val savedOut: MutableStateFlow<List<ReceivedFileRecord>>,
    private val completeOut: MutableStateFlow<Boolean>,
    private val failedOut: MutableStateFlow<String?>
) {
    private var sendJob: Job? = null
    private var recvJob: Job? = null
    private var receiveRoot: File? = null
    private var currentOut: FileOutputStream? = null
    private var currentPartial: File? = null
    private var currentFinal: File? = null
    private var currentPath: String? = null
    private var currentFileIndex: Int = -1
    private var currentExpected: Long = -1L
    private var currentWritten: Long = 0L
    private val pendingSaved = mutableListOf<ReceivedFileRecord>()
    private var receiveQueue: List<TransferQueueItemUi> = emptyList()
    private var expectedFileCount: Int = 0
    private var receiveTotalBytes = 0L
    private var receiveDoneBytes = 0L
    private var speedWindowStartMs = 0L
    private var speedWindowBytes = 0L
    private var lastMeasuredSpeed = 0L
    private val guestReady = MutableStateFlow(false)
    private val guestAcked = MutableStateFlow(false)
    @Volatile private var active = false

    fun prepareGuestSink(context: Context, sessionId: String, expected: List<SharedFileInfo>) {
        val root = File(context.getExternalFilesDir("received"), sessionId)
        if (receiveRoot == null) {
            if (root.exists()) root.deleteRecursively()
            root.mkdirs()
            receiveRoot = root
        }
        if (expected.isNotEmpty()) {
            expectedFileCount = expected.size
            receiveTotalBytes = expected.sumOf { it.sizeBytes.coerceAtLeast(0L) }.coerceAtLeast(1L)
            val knownTotal = expected.sumOf { it.sizeBytes.coerceAtLeast(0L) }
            TransferLimits.insufficientSpaceMessage(root, knownTotal)?.let { reason ->
                fail(reason)
                return
            }
            for (item in expected) {
                if (item.sizeBytes > TransferLimits.MAX_FILE_BYTES) {
                    fail(TransferLimits.tooLargeMessage(item.name))
                    return
                }
            }
            receiveQueue = expected.map { TransferQueueItemUi(it.name, QueueItemStatus.Waiting) }
            progressOut.value = TransferProgressUi(
                sending = false,
                bytesDone = 0,
                bytesTotal = receiveTotalBytes,
                currentFileName = expected.first().name,
                currentFileDone = 0,
                currentFileTotal = expected.first().sizeBytes.coerceAtLeast(1L),
                speedBytesPerSec = 0,
                etaSeconds = null,
                queue = receiveQueue
            )
        }
    }

    fun startHostSend(context: Context, entries: List<LocalShareEntry>) {
        sendJob?.cancel()
        completeOut.value = false
        failedOut.value = null
        guestReady.value = false
        active = true
        // Collect guest READY frames while sending.
        recvJob?.cancel()
        recvJob = scope.launch(Dispatchers.IO) {
            try {
                session.incoming.collect { frame ->
                    if (!active) return@collect
                    handleHostInbound(frame)
                }
            } catch (_: Throwable) {
                // Channel closed on session teardown.
            }
        }
        sendJob = scope.launch(Dispatchers.IO) {
            try {
                if (!session.awaitDataChannelOpen(1_000L)) {
                    fail("DataChannel not open")
                    return@launch
                }
                // Wait until guest is collecting — avoids sending into a void.
                val ready = withTimeoutOrNull(GUEST_READY_TIMEOUT_MS) {
                    while (isActive && active) {
                        if (guestReady.value) return@withTimeoutOrNull true
                        // Nudge guest in case READY was missed.
                        sendFrame(encodeHello())
                        delay(200)
                        if (guestReady.value) return@withTimeoutOrNull true
                    }
                    false
                } == true
                if (!ready) {
                    fail("Receiver did not become ready")
                    return@launch
                }
                sendFrame(encodeHello())
                // Prefer actual readable length when SAF size is wrong/missing.
                val sizedEntries = entries.map { entry ->
                    val actual = actualContentLength(context, entry)
                    entry to actual
                }
                val total = sizedEntries.sumOf { (_, size) -> size.coerceAtLeast(1L) }.coerceAtLeast(1L)
                val queue = sizedEntries.map { (entry, _) ->
                    TransferQueueItemUi(entry.relativePath, QueueItemStatus.Waiting)
                }.toMutableList()
                var overallDone = 0L
                resetSpeedWindow()
                for ((index, pair) in sizedEntries.withIndex()) {
                    val (entry, declaredSize) = pair
                    if (!isActive || !active) return@launch
                    if (declaredSize > TransferLimits.MAX_FILE_BYTES) {
                        fail(TransferLimits.tooLargeMessage(entry.displayName))
                        return@launch
                    }
                    queue[index] = queue[index].copy(status = QueueItemStatus.Active)
                    val path = InternetCodeSignaling.sanitizeWirePath(entry.relativePath)
                        ?: entry.relativePath.take(180)
                    sendFrame(encodeFileBegin(index, path, declaredSize.coerceAtLeast(0L)))
                    if (!session.awaitSendBufferLow()) {
                        fail("DataChannel send buffer stalled")
                        return@launch
                    }
                    val fileTotal = declaredSize.coerceAtLeast(1L)
                    var fileDone = 0L
                    val stream = context.contentResolver.openInputStream(entry.uri)
                    if (stream == null) {
                        fail("Could not read ${entry.displayName}")
                        return@launch
                    }
                    stream.use { input ->
                        val buf = ByteArray(CHUNK_BYTES)
                        while (isActive && active) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            val chunk = if (n == buf.size) buf else buf.copyOf(n)
                            if (!session.awaitSendBufferLow()) {
                                fail("DataChannel send buffer stalled")
                                return@launch
                            }
                            if (!sendFrame(encodeChunk(index, fileDone, chunk))) {
                                fail("DataChannel send failed")
                                return@launch
                            }
                            fileDone += n
                            overallDone += n
                            val speed = noteBytes(n.toLong())
                            val remain = (total - overallDone).coerceAtLeast(0L)
                            val eta = if (speed > 0L) (remain / speed).coerceAtLeast(1L) else null
                            progressOut.value = TransferProgressUi(
                                sending = true,
                                bytesDone = overallDone.coerceAtMost(total),
                                bytesTotal = total,
                                currentFileName = entry.relativePath,
                                currentFileDone = fileDone.coerceAtMost(fileTotal),
                                currentFileTotal = fileTotal,
                                speedBytesPerSec = speed,
                                etaSeconds = eta,
                                queue = queue.toList()
                            )
                            if (fileDone % (CHUNK_BYTES * 4L) < CHUNK_BYTES) yield()
                        }
                    }
                    // Announce the bytes actually sent (not the possibly-wrong SAF size).
                    sendFrame(encodeFileDone(index, path, fileDone))
                    if (!session.awaitSendBufferLow()) {
                        fail("DataChannel send buffer stalled")
                        return@launch
                    }
                    queue[index] = queue[index].copy(status = QueueItemStatus.Done)
                }
                // Flush remaining SCTP buffer so the guest actually gets the tail (~last 10%).
                if (!session.awaitSendBufferDrained()) {
                    fail("DataChannel did not finish flushing")
                    return@launch
                }
                repeat(3) {
                    sendFrame(encodeXferDone())
                    if (guestAcked.value) return@repeat
                    delay(150)
                }
                if (!session.awaitSendBufferDrained()) {
                    fail("DataChannel did not finish flushing")
                    return@launch
                }
                val acked = withTimeoutOrNull(XFER_ACK_TIMEOUT_MS) {
                    while (isActive && active) {
                        if (guestAcked.value) return@withTimeoutOrNull true
                        sendFrame(encodeXferDone())
                        delay(250)
                    }
                    false
                } == true
                progressOut.value = TransferProgressUi(
                    sending = true,
                    bytesDone = total,
                    bytesTotal = total,
                    currentFileName = null,
                    currentFileDone = 0,
                    currentFileTotal = 0,
                    speedBytesPerSec = lastMeasuredSpeed,
                    etaSeconds = 0,
                    queue = queue.toList()
                )
                if (!acked) {
                    AgentDebugLog.log(
                        hypothesisId = "H-WEBRTC",
                        location = "DataChannelFileTransfer.hostSend",
                        message = "guest ACK missing after drain; marking host complete anyway",
                        data = emptyMap(),
                        runId = "webrtc"
                    )
                }
                completeOut.value = true
            } catch (t: Throwable) {
                fail(t.message ?: "WebRTC send failed")
            }
        }
    }

    private fun handleHostInbound(frame: ByteArray) {
        if (frame.isEmpty()) return
        when (frame[0]) {
            TYPE_READY -> guestReady.value = true
            TYPE_XFER_ACK -> guestAcked.value = true
        }
    }

    fun startGuestReceive() {
        recvJob?.cancel()
        active = true
        guestReady.value = false
        guestAcked.value = false
        recvJob = scope.launch(Dispatchers.IO) {
            try {
                launch {
                    if (session.awaitDataChannelOpen(WEBRTC_OPEN_WAIT_MS)) {
                        sendFrame(encodeReady())
                    }
                }
                session.incoming.collect { frame ->
                    if (!active) return@collect
                    handleFrame(frame)
                }
            } catch (t: Throwable) {
                if (active) fail(t.message ?: "WebRTC receive failed")
            }
        }
    }

    fun reset() {
        active = false
        sendJob?.cancel()
        sendJob = null
        recvJob?.cancel()
        recvJob = null
        discardIncompleteReceive()
        receiveRoot = null
        pendingSaved.clear()
        receiveQueue = emptyList()
        expectedFileCount = 0
        receiveTotalBytes = 0L
        receiveDoneBytes = 0L
        resetSpeedWindow()
    }

    private fun handleFrame(frame: ByteArray) {
        if (frame.size < 5) return
        val type = frame[0]
        val len = ByteBuffer.wrap(frame, 1, 4).order(ByteOrder.BIG_ENDIAN).int
        if (len < 0 || 5 + len > frame.size) return
        val payload = if (len == 0) ByteArray(0) else frame.copyOfRange(5, 5 + len)

        when (type) {
            TYPE_HELLO -> {
                sendFrame(encodeReady())
            }
            TYPE_READY -> Unit
            TYPE_FILE_BEGIN -> {
                val buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
                val index = buf.int
                val pathLen = buf.short.toInt() and 0xffff
                if (pathLen <= 0 || pathLen > 180 || buf.remaining() < pathLen + 8) return
                val pathBytes = ByteArray(pathLen)
                buf.get(pathBytes)
                val path = InternetCodeSignaling.sanitizeWirePath(String(pathBytes, StandardCharsets.UTF_8))
                    ?: return
                val size = buf.long
                val root = receiveRoot
                if (root == null) {
                    fail("Receiver wasn’t ready")
                    return
                }
                discardIncompleteReceive()
                val safe = path.replace('/', '_').take(160)
                val finalFile = File(root, safe)
                val partial = File(root, safe + PARTIAL_SUFFIX)
                runCatching { if (partial.exists()) partial.delete() }
                runCatching { if (finalFile.exists()) finalFile.delete() }
                partial.parentFile?.mkdirs()
                currentOut = FileOutputStream(partial)
                currentPartial = partial
                currentFinal = finalFile
                currentPath = path
                currentFileIndex = index
                currentExpected = size
                currentWritten = 0L
                resetSpeedWindow()
                if (receiveTotalBytes <= 0L && size > 0L) {
                    receiveTotalBytes = size.coerceAtLeast(1L)
                }
                receiveQueue = if (receiveQueue.none { it.name == path }) {
                    receiveQueue + TransferQueueItemUi(path, QueueItemStatus.Active)
                } else {
                    receiveQueue.map {
                        when {
                            it.name == path -> it.copy(status = QueueItemStatus.Active)
                            it.status == QueueItemStatus.Active -> it.copy(status = QueueItemStatus.Waiting)
                            else -> it
                        }
                    }
                }
            }
            TYPE_CHUNK -> {
                val buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
                if (buf.remaining() < 12) return
                val index = buf.int
                val offset = buf.long
                if (index != currentFileIndex) return
                if (offset != currentWritten) {
                    fail("Transfer desync on ${currentPath ?: "?"}")
                    return
                }
                val data = ByteArray(buf.remaining())
                buf.get(data)
                currentOut?.write(data)
                currentWritten += data.size
                receiveDoneBytes += data.size
                val speed = noteBytes(data.size.toLong())
                val remain = (receiveTotalBytes - receiveDoneBytes).coerceAtLeast(0L)
                val eta = if (speed > 0L) (remain / speed).coerceAtLeast(1L) else null
                val path = currentPath
                progressOut.value = TransferProgressUi(
                    sending = false,
                    bytesDone = receiveDoneBytes.coerceAtMost(receiveTotalBytes),
                    bytesTotal = receiveTotalBytes,
                    currentFileName = path,
                    currentFileDone = currentWritten,
                    currentFileTotal = currentExpected.coerceAtLeast(1L),
                    speedBytesPerSec = speed,
                    etaSeconds = eta,
                    queue = receiveQueue
                )
            }
            TYPE_FILE_DONE -> {
                val buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
                if (buf.remaining() < 6) return
                val index = buf.int
                val pathLen = buf.short.toInt() and 0xffff
                if (pathLen <= 0 || buf.remaining() < pathLen + 8) return
                val pathBytes = ByteArray(pathLen)
                buf.get(pathBytes)
                val path = InternetCodeSignaling.sanitizeWirePath(String(pathBytes, StandardCharsets.UTF_8))
                    ?: return
                if (index != currentFileIndex || path != currentPath) return
                val expectedSize = buf.long
                val partial = currentPartial
                val finalFile = currentFinal
                runCatching { currentOut?.flush() }
                runCatching { currentOut?.close() }
                currentOut = null
                currentPartial = null
                currentFinal = null
                currentPath = null
                currentFileIndex = -1
                if (partial == null || finalFile == null || !partial.exists()) {
                    fail("Incomplete file on disk for $path")
                    return
                }
                val sizeOk = expectedSize < 0L || partial.length() == expectedSize
                if (!sizeOk) {
                    partial.delete()
                    fail("Size mismatch for $path")
                    return
                }
                if (finalFile.exists()) finalFile.delete()
                val renamed = partial.renameTo(finalFile) || run {
                    partial.copyTo(finalFile, overwrite = true)
                    partial.delete()
                    finalFile.exists()
                }
                if (!renamed || !finalFile.exists()) {
                    partial.delete()
                    fail("Could not finalize $path")
                    return
                }
                pendingSaved += ReceivedFileRecord(
                    name = path,
                    sizeBytes = finalFile.length(),
                    downloaded = true,
                    localUri = Uri.fromFile(finalFile).toString()
                )
                savedOut.value = pendingSaved.toList()
                receiveQueue = receiveQueue.map {
                    if (it.name == path) it.copy(status = QueueItemStatus.Done) else it
                }
                // Manifest sizes can overestimate; keep the bar honest as files finish.
                if (receiveDoneBytes > receiveTotalBytes) {
                    receiveTotalBytes = receiveDoneBytes
                }
                progressOut.value = TransferProgressUi(
                    sending = false,
                    bytesDone = receiveDoneBytes.coerceAtMost(receiveTotalBytes),
                    bytesTotal = receiveTotalBytes.coerceAtLeast(1L),
                    currentFileName = path,
                    currentFileDone = finalFile.length(),
                    currentFileTotal = finalFile.length().coerceAtLeast(1L),
                    speedBytesPerSec = lastMeasuredSpeed,
                    etaSeconds = null,
                    queue = receiveQueue
                )
                maybeCompleteIfAllFilesSaved()
            }
            TYPE_XFER_DONE -> {
                discardIncompleteReceive()
                markGuestComplete()
            }
            TYPE_XFER_ACK -> Unit
        }
    }

    private fun maybeCompleteIfAllFilesSaved() {
        if (completeOut.value) return
        if (expectedFileCount <= 0) return
        if (pendingSaved.count { it.downloaded } < expectedFileCount) return
        markGuestComplete()
    }

    private fun markGuestComplete() {
        if (completeOut.value) return
        savedOut.value = pendingSaved.toList()
        if (receiveDoneBytes > 0L) {
            receiveTotalBytes = maxOf(receiveTotalBytes, receiveDoneBytes)
        }
        progressOut.value = TransferProgressUi(
            sending = false,
            bytesDone = receiveTotalBytes.coerceAtLeast(1L),
            bytesTotal = receiveTotalBytes.coerceAtLeast(1L),
            currentFileName = null,
            currentFileDone = 0,
            currentFileTotal = 0,
            speedBytesPerSec = lastMeasuredSpeed,
            etaSeconds = 0,
            queue = receiveQueue.map {
                if (it.status == QueueItemStatus.Active) it.copy(status = QueueItemStatus.Done) else it
            }
        )
        sendFrame(encodeXferAck())
        completeOut.value = true
        AgentDebugLog.log(
            hypothesisId = "H-WEBRTC",
            location = "DataChannelFileTransfer.markGuestComplete",
            message = "guest WebRTC transfer complete",
            data = mapOf("saved" to pendingSaved.size, "expected" to expectedFileCount),
            runId = "webrtc"
        )
    }

    private fun sendFrame(frame: ByteArray): Boolean = session.send(frame)

    private fun discardIncompleteReceive() {
        runCatching { currentOut?.close() }
        currentOut = null
        currentPartial?.let { runCatching { if (it.exists()) it.delete() } }
        currentPartial = null
        currentFinal = null
        currentPath = null
        currentFileIndex = -1
    }

    private fun resetSpeedWindow() {
        speedWindowStartMs = System.currentTimeMillis()
        speedWindowBytes = 0L
        lastMeasuredSpeed = 0L
    }

    private fun noteBytes(n: Long): Long {
        val now = System.currentTimeMillis()
        if (speedWindowStartMs == 0L) speedWindowStartMs = now
        speedWindowBytes += n
        val elapsed = (now - speedWindowStartMs).coerceAtLeast(1L)
        if (elapsed >= 750L) {
            lastMeasuredSpeed = (speedWindowBytes * 1000L) / elapsed
            speedWindowStartMs = now
            speedWindowBytes = 0L
        } else if (lastMeasuredSpeed == 0L) {
            lastMeasuredSpeed = (speedWindowBytes * 1000L) / elapsed
        }
        return lastMeasuredSpeed
    }

    private fun fail(reason: String) {
        active = false
        failedOut.value = reason
        discardIncompleteReceive()
        AgentDebugLog.log(
            hypothesisId = "H-WEBRTC",
            location = "DataChannelFileTransfer.fail",
            message = "webrtc transfer failed",
            data = mapOf("reason" to reason),
            runId = "webrtc"
        )
    }

    private fun actualContentLength(context: Context, entry: LocalShareEntry): Long {
        if (entry.sizeBytes > 0L) return entry.sizeBytes
        return runCatching {
            context.contentResolver.openAssetFileDescriptor(entry.uri, "r")?.use { it.length }
        }.getOrNull()?.takeIf { it >= 0L } ?: 0L
    }

    companion object {
        private const val CHUNK_BYTES = 64 * 1024
        private const val PARTIAL_SUFFIX = ".partial"
        private const val GUEST_READY_TIMEOUT_MS = 12_000L
        private const val XFER_ACK_TIMEOUT_MS = 20_000L
        private const val WEBRTC_OPEN_WAIT_MS = 20_000L

        const val TYPE_HELLO: Byte = 1
        const val TYPE_FILE_BEGIN: Byte = 2
        const val TYPE_READY: Byte = 3
        const val TYPE_CHUNK: Byte = 5
        const val TYPE_FILE_DONE: Byte = 8
        const val TYPE_XFER_DONE: Byte = 9
        const val TYPE_XFER_ACK: Byte = 10

        private fun frame(type: Byte, payload: ByteArray): ByteArray {
            val out = ByteArray(5 + payload.size)
            out[0] = type
            ByteBuffer.wrap(out, 1, 4).order(ByteOrder.BIG_ENDIAN).putInt(payload.size)
            System.arraycopy(payload, 0, out, 5, payload.size)
            return out
        }

        fun encodeHello(): ByteArray = frame(TYPE_HELLO, byteArrayOf(1))
        fun encodeReady(): ByteArray = frame(TYPE_READY, ByteArray(0))
        fun encodeXferAck(): ByteArray = frame(TYPE_XFER_ACK, ByteArray(0))

        fun encodeFileBegin(index: Int, path: String, size: Long): ByteArray {
            val pathBytes = path.toByteArray(StandardCharsets.UTF_8)
            val buf = ByteBuffer.allocate(4 + 2 + pathBytes.size + 8).order(ByteOrder.BIG_ENDIAN)
            buf.putInt(index)
            buf.putShort(pathBytes.size.toShort())
            buf.put(pathBytes)
            buf.putLong(size)
            return frame(TYPE_FILE_BEGIN, buf.array())
        }

        fun encodeChunk(index: Int, offset: Long, data: ByteArray): ByteArray {
            val buf = ByteBuffer.allocate(4 + 8 + data.size).order(ByteOrder.BIG_ENDIAN)
            buf.putInt(index)
            buf.putLong(offset)
            buf.put(data)
            return frame(TYPE_CHUNK, buf.array())
        }

        fun encodeFileDone(index: Int, path: String, size: Long): ByteArray {
            val pathBytes = path.toByteArray(StandardCharsets.UTF_8)
            val buf = ByteBuffer.allocate(4 + 2 + pathBytes.size + 8).order(ByteOrder.BIG_ENDIAN)
            buf.putInt(index)
            buf.putShort(pathBytes.size.toShort())
            buf.put(pathBytes)
            buf.putLong(size)
            return frame(TYPE_FILE_DONE, buf.array())
        }

        fun encodeXferDone(): ByteArray = frame(TYPE_XFER_DONE, ByteArray(0))
    }
}
