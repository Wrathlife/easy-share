package com.netshare.app.signaling

import android.content.Context
import android.net.Uri
import com.netshare.app.debug.AgentDebugLog
import com.netshare.app.files.LocalShareEntry
import com.netshare.app.history.ReceivedFileRecord
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
import kotlinx.coroutines.yield
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Base64

/**
 * Chunked file transfer over the already-encrypted MQTT signaling channel.
 * Temporary until WebRTC DataChannels carry bytes peer-to-peer.
 *
 * Incomplete receives are written as `*.partial` and only renamed after a verified `fdone`.
 * A crash mid-transfer leaves an incomplete `.partial` (never offered as a finished file).
 */
class MqttEncryptedFileTransfer(
    private val scope: CoroutineScope,
    private val publishSealed: (String, Int) -> Unit,
    private val authKey: () -> ByteArray,
    private val sessionExp: () -> Long,
    private val isLive: () -> Boolean,
    private val awaitConnected: suspend (Long) -> Boolean,
    private val progressOut: MutableStateFlow<TransferProgressUi?>,
    private val savedOut: MutableStateFlow<List<ReceivedFileRecord>>,
    private val completeOut: MutableStateFlow<Boolean>,
    private val failedOut: MutableStateFlow<String?>
) {
    private var sendJob: Job? = null
    private var receiveRoot: File? = null
    private var currentOut: FileOutputStream? = null
    private var currentPartial: File? = null
    private var currentFinal: File? = null
    private var currentPath: String? = null
    private var currentExpected: Long = -1L
    private var currentWritten: Long = 0L
    private var currentSeq = 0
    private val pendingSaved = mutableListOf<ReceivedFileRecord>()
    private var receiveQueue: List<TransferQueueItemUi> = emptyList()
    private var receiveTotalBytes = 0L
    private var receiveDoneBytes = 0L
    private var receiveStarted = false
    private var speedWindowStartMs = 0L
    private var speedWindowBytes = 0L
    private var lastMeasuredSpeed = 0L
    @Volatile private var brokerDown = false

    /** Guest: drop in-progress partial so a re-sent file can start clean after reconnect. */
    fun onBrokerDisconnected() {
        brokerDown = true
        discardIncompleteReceive()
    }

    fun reset() {
        sendJob?.cancel()
        sendJob = null
        discardIncompleteReceive()
        receiveRoot = null
        receiveStarted = false
        brokerDown = false
        pendingSaved.clear()
        receiveQueue = emptyList()
        receiveTotalBytes = 0L
        receiveDoneBytes = 0L
        speedWindowStartMs = 0L
        speedWindowBytes = 0L
        lastMeasuredSpeed = 0L
        progressOut.value = null
        savedOut.value = emptyList()
        completeOut.value = false
        failedOut.value = null
    }

    fun prepareGuestSink(context: Context, sessionId: String, expected: List<SharedFileInfo>) {
        val root = File(context.getExternalFilesDir("received"), sessionId)
        if (receiveRoot == null) {
            if (root.exists() && !receiveStarted) root.deleteRecursively()
            root.mkdirs()
            receiveRoot = root
            // Leftover partials from a killed session must never look like finished files.
            root.listFiles()?.forEach { f ->
                if (f.isFile && f.name.endsWith(PARTIAL_SUFFIX)) f.delete()
            }
        }
        if (receiveStarted) return
        if (expected.isNotEmpty()) {
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
            receiveTotalBytes = expected.sumOf { it.sizeBytes.coerceAtLeast(1L) }.coerceAtLeast(1L)
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
        sendJob = scope.launch(Dispatchers.IO) {
            try {
                // Brief pause so the guest can finish preparing the sink after confirm.
                delay(400)
                val total = entries.sumOf { it.sizeBytes.coerceAtLeast(1L) }.coerceAtLeast(1L)
                val queue = entries.map { TransferQueueItemUi(it.relativePath, QueueItemStatus.Waiting) }.toMutableList()
                var overallDone = 0L
                resetSpeedWindow()
                for ((index, entry) in entries.withIndex()) {
                    if (!isLive() || !isActive) return@launch
                    if (entry.sizeBytes > TransferLimits.MAX_FILE_BYTES) {
                        fail(TransferLimits.tooLargeMessage(entry.displayName))
                        return@launch
                    }
                    queue[index] = queue[index].copy(status = QueueItemStatus.Active)
                    // Restart this file if the broker drops mid-send (guest discards partial).
                    var fileAttempts = 0
                    fileRetry@ while (isActive && isLive()) {
                        fileAttempts++
                        if (fileAttempts > MAX_FILE_RETRIES) {
                            fail("Connection kept dropping while sending “${entry.displayName}”")
                            return@launch
                        }
                        if (!awaitConnected(RECONNECT_WAIT_MS)) {
                            fail("Connection lost while sending")
                            return@launch
                        }
                        brokerDown = false
                        publishFileEvent(
                            event = "fstart",
                            path = entry.relativePath,
                            size = entry.sizeBytes.coerceAtLeast(0L),
                            seq = 0,
                            digest = "",
                            dataB64 = "",
                            qos = 1
                        )
                        val fileTotal = entry.sizeBytes.coerceAtLeast(1L)
                        var fileDone = 0L
                        val baseOverall = overallDone
                        val stream = context.contentResolver.openInputStream(entry.uri)
                        if (stream == null) {
                            fail("Could not read ${entry.displayName}")
                            return@launch
                        }
                        val sendResult = stream.use { input ->
                            val chunkBytes = preferredChunkBytes(entry.sizeBytes)
                            val buf = ByteArray(chunkBytes)
                            var seq = 0
                            while (isActive && isLive()) {
                                if (brokerDown || !awaitConnected(RECONNECT_WAIT_MS)) {
                                    overallDone = baseOverall
                                    return@use SendFileResult.Restart
                                }
                                val n = input.read(buf)
                                if (n <= 0) break
                                val chunk = if (n == buf.size) buf.copyOf() else buf.copyOf(n)
                                val digest = sha256Hex(chunk)
                                val b64 = Base64.getEncoder().encodeToString(chunk)
                                publishFileEvent(
                                    event = "fbin",
                                    path = entry.relativePath,
                                    size = entry.sizeBytes,
                                    seq = seq,
                                    digest = digest,
                                    dataB64 = b64,
                                    qos = 0
                                )
                                if (brokerDown) {
                                    overallDone = baseOverall
                                    return@use SendFileResult.Restart
                                }
                                seq++
                                fileDone += n
                                overallDone = baseOverall + fileDone
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
                                if (seq % 4 == 0) yield()
                            }
                            SendFileResult.Ok
                        }
                        when (sendResult) {
                            SendFileResult.Restart -> continue@fileRetry
                            SendFileResult.Ok -> {
                                if (brokerDown || !awaitConnected(RECONNECT_WAIT_MS)) {
                                    overallDone = baseOverall
                                    continue@fileRetry
                                }
                                publishFileEvent(
                                    event = "fdone",
                                    path = entry.relativePath,
                                    size = entry.sizeBytes,
                                    seq = 0,
                                    digest = "",
                                    dataB64 = "",
                                    qos = 1
                                )
                                overallDone = baseOverall + entry.sizeBytes.coerceAtLeast(0L)
                                break@fileRetry
                            }
                        }
                    }
                    queue[index] = queue[index].copy(status = QueueItemStatus.Done)
                }
                publishSignedSimple("h", "xfer-complete")
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
                completeOut.value = true
            } catch (t: Throwable) {
                fail(t.message ?: "Send failed")
            }
        }
    }

    /** Handle inbound transfer events on guest (already MAC/freshness verified). */
    fun onGuestEvent(event: String, obj: JSONObject) {
        when (event) {
            "fstart" -> {
                brokerDown = false
                val path = InternetCodeSignaling.sanitizeWirePath(obj.optString("path")) ?: return
                val root = receiveRoot
                if (root == null) {
                    fail("Receiver wasn’t ready when transfer started — ask sharer to send again")
                    return
                }
                val size = obj.optLong("size", -1L)
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
                currentExpected = size
                currentWritten = 0L
                currentSeq = 0
                receiveStarted = true
                resetSpeedWindow()
                if (receiveTotalBytes <= 0L && size > 0L) {
                    receiveTotalBytes = size.coerceAtLeast(1L)
                }
                if (receiveQueue.none { it.name == path }) {
                    receiveQueue = receiveQueue + TransferQueueItemUi(path, QueueItemStatus.Active)
                } else {
                    receiveQueue = receiveQueue.map {
                        when {
                            it.name == path -> it.copy(status = QueueItemStatus.Active)
                            it.status == QueueItemStatus.Active -> it.copy(status = QueueItemStatus.Waiting)
                            else -> it
                        }
                    }
                }
            }
            "fbin" -> {
                val path = InternetCodeSignaling.sanitizeWirePath(obj.optString("path")) ?: return
                if (path != currentPath) return
                val seq = obj.optInt("seq", -1)
                if (seq != currentSeq) {
                    fail("Transfer desync on $path")
                    return
                }
                val digest = obj.optString("digest")
                val b64 = obj.optString("d")
                val bytes = runCatching { Base64.getDecoder().decode(b64) }.getOrNull() ?: return
                if (sha256Hex(bytes) != digest) {
                    fail("Chunk integrity failed for $path")
                    return
                }
                currentOut?.write(bytes)
                currentWritten += bytes.size
                currentSeq++
                receiveDoneBytes += bytes.size
                val speed = noteBytes(bytes.size.toLong())
                val remain = (receiveTotalBytes - receiveDoneBytes).coerceAtLeast(0L)
                val eta = if (speed > 0L) (remain / speed).coerceAtLeast(1L) else null
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
            "fdone" -> {
                val path = InternetCodeSignaling.sanitizeWirePath(obj.optString("path")) ?: return
                if (path != currentPath) return
                val partial = currentPartial
                val finalFile = currentFinal
                runCatching { currentOut?.flush() }
                runCatching { currentOut?.close() }
                currentOut = null
                currentPartial = null
                currentFinal = null
                currentPath = null

                if (partial == null || finalFile == null || !partial.exists()) {
                    fail("Incomplete file on disk for $path")
                    return
                }
                if (currentExpected >= 0L && partial.length() != currentExpected) {
                    partial.delete()
                    fail("Size mismatch for $path (got ${partial.length()}, expected $currentExpected)")
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
            }
            "xfer-complete" -> {
                discardIncompleteReceive()
                savedOut.value = pendingSaved.toList()
                completeOut.value = true
                AgentDebugLog.log(
                    hypothesisId = "H-XFER",
                    location = "MqttEncryptedFileTransfer.xfer-complete",
                    message = "guest transfer complete",
                    data = mapOf("saved" to pendingSaved.size),
                    runId = "xfer-speed"
                )
            }
        }
    }

    private fun discardIncompleteReceive() {
        runCatching { currentOut?.close() }
        currentOut = null
        currentPartial?.let { runCatching { if (it.exists()) it.delete() } }
        currentPartial = null
        currentFinal = null
        currentPath = null
    }

    private fun resetSpeedWindow() {
        speedWindowStartMs = System.currentTimeMillis()
        speedWindowBytes = 0L
        lastMeasuredSpeed = 0L
    }

    /** Sliding ~0.75s window for displayed B/s. */
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
        failedOut.value = reason
        discardIncompleteReceive()
        AgentDebugLog.log(
            hypothesisId = "H-XFER",
            location = "MqttEncryptedFileTransfer.fail",
            message = "transfer failed",
            data = mapOf("reason" to reason),
            runId = "xfer-speed"
        )
    }

    private fun publishFileEvent(
        event: String,
        path: String,
        size: Long,
        seq: Int,
        digest: String,
        dataB64: String,
        qos: Int
    ) {
        val key = authKey()
        if (key.isEmpty()) return
        val ts = System.currentTimeMillis() / 1000L
        val exp = sessionExp()
        val nonce = SignalingCrypto.randomNonce()
        val extra = listOf(path, size.toString(), seq.toString(), digest).joinToString("|")
        val mac = SignalingCrypto.macHex(
            key,
            SignalingCrypto.canonical("h", event, ts, exp, nonce, extra)
        )
        val inner = JSONObject()
            .put("r", "h")
            .put("e", event)
            .put("ts", ts)
            .put("exp", exp)
            .put("nonce", nonce)
            .put("mac", mac)
            .put("path", path)
            .put("size", size)
            .put("seq", seq)
            .put("digest", digest)
            .put("d", dataB64)
            .toString()
        publishSealed(inner, qos)
    }

    private fun publishSignedSimple(roleChar: String, event: String) {
        val key = authKey()
        if (key.isEmpty()) return
        val ts = System.currentTimeMillis() / 1000L
        val exp = sessionExp()
        val nonce = SignalingCrypto.randomNonce()
        val mac = SignalingCrypto.macHex(
            key,
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
        publishSealed(inner, 1)
    }

    companion object {
        private const val PARTIAL_SUFFIX = ".partial"
        private const val RECONNECT_WAIT_MS = 45_000L
        private const val MAX_FILE_RETRIES = 3
        private const val KIB = 1024

        private enum class SendFileResult { Ok, Restart }

        /**
         * Size-based MQTT chunks (AES-GCM envelopes).
         * ≤ ~180 KiB → single-shot; else prefer ~180 / 128 / 96 KiB.
         */
        fun preferredChunkBytes(fileSize: Long): Int {
            val size = fileSize.coerceAtLeast(0L)
            return when {
                size <= 180L * KIB -> size.toInt().coerceAtLeast(1)
                size <= 2L * 1024 * KIB -> 180 * KIB
                size <= 20L * 1024 * KIB -> 128 * KIB
                else -> 96 * KIB
            }
        }

        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }

        fun transferExtra(obj: JSONObject): String {
            val path = obj.optString("path")
            val size = obj.optLong("size", 0L)
            val seq = obj.optInt("seq", 0)
            val digest = obj.optString("digest")
            return listOf(path, size.toString(), seq.toString(), digest).joinToString("|")
        }
    }
}
