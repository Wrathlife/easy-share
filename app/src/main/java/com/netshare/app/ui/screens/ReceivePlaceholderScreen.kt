package com.netshare.app.ui.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.netshare.app.ads.InterstitialAds
import com.netshare.app.ads.findActivity
import com.netshare.app.connect.ConnectivitySnapshot
import com.netshare.app.connect.canUseCurrentNetwork
import com.netshare.app.debug.AgentDebugLog
import com.netshare.app.files.ReceivedFileOpener
import com.netshare.app.history.ReceivedFileRecord
import com.netshare.app.history.ReceivedHistoryStore
import com.netshare.app.history.ReceivedSessionRecord
import com.netshare.app.signaling.InternetCodeSignaling
import com.netshare.app.signaling.PairingSignalState
import com.netshare.app.signaling.SharedFileInfo
import com.netshare.app.ui.components.ConnectStepper
import com.netshare.app.ui.components.MobileDataNetworkControls
import com.netshare.app.ui.components.PairingCodeField
import com.netshare.app.ui.components.PairingConfirmCard
import com.netshare.app.ui.components.ReceiveResultCard
import com.netshare.app.ui.components.ReceiveResultUi
import com.netshare.app.ui.components.SessionStatusHeader
import com.netshare.app.ui.components.TransferProgressPanel
import com.netshare.app.ui.components.formatBytes
import com.netshare.app.ui.state.ConnectStep
import com.netshare.app.ui.state.QueueItemStatus
import com.netshare.app.ui.state.SessionUiState
import com.netshare.app.ui.state.TransferProgressUi
import com.netshare.app.ui.state.TransferQueueItemUi
import com.netshare.app.webrtc.PairingCode

private enum class ReceivePhase { EnterCode, Connecting, Confirming, Transferring, Result }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceivePlaceholderScreen(
    snapshot: ConnectivitySnapshot,
    useMobileData: Boolean,
    onUseMobileDataChange: (Boolean) -> Unit,
    encryptFileTransfer: Boolean = false,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val signaling = remember { InternetCodeSignaling(scope) }
    val history = remember { ReceivedHistoryStore(context) }
    val signalState by signaling.state.collectAsState()
    val remoteFiles by signaling.remoteFiles.collectAsState()

    var code by remember { mutableStateOf("") }
    var phase by remember { mutableStateOf(ReceivePhase.EnterCode) }
    var warnedHistoryUnavailable by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ReceiveResultUi?>(null) }
    val transferProgress by signaling.transferProgress.collectAsState()
    val transferComplete by signaling.transferComplete.collectAsState()
    val transferFailed by signaling.transferFailed.collectAsState()
    val savedFiles by signaling.savedFiles.collectAsState()
    val networkAllowed = canUseCurrentNetwork(snapshot, useMobileData)

    fun dismissAndLeave() {
        signaling.stop()
        result = null
        phase = ReceivePhase.EnterCode
        code = ""
        onBack()
    }

    /** Stop the current pairing/transfer and return to the code field to try again. */
    fun cancelAttempt() {
        signaling.stop()
        result = null
        phase = ReceivePhase.EnterCode
    }

    BackHandler {
        if (phase == ReceivePhase.EnterCode || phase == ReceivePhase.Result) {
            dismissAndLeave()
        } else {
            cancelAttempt()
        }
    }

    DisposableEffect(Unit) {
        onDispose { signaling.stop() }
    }

    fun showResult(outcome: ReceiveResultUi) {
        result = outcome
        phase = ReceivePhase.Result
    }

    fun showReceived(files: List<ReceivedFileRecord>) {
        val normalized = PairingCode.normalize(code)
        if (PairingCode.isValidShort(normalized)) {
            history.upsertSession(
                ReceivedSessionRecord(
                    id = com.netshare.app.signaling.SignalingCrypto.topicId(normalized),
                    shareCode = ReceivedHistoryStore.redactShareCode(normalized),
                    receivedAtEpochMs = System.currentTimeMillis(),
                    files = files
                )
            )
            if (!history.encryptionAvailable && !warnedHistoryUnavailable) {
                warnedHistoryUnavailable = true
                Toast.makeText(
                    context,
                    "Files saved, but encrypted history storage is unavailable on this device",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        val downloadedCount = files.count { it.downloaded }
        showResult(
            ReceiveResultUi(
                received = downloadedCount > 0,
                title = when {
                    downloadedCount == 0 -> "Nothing saved"
                    downloadedCount == 1 -> "File received"
                    else -> "Files received"
                },
                message = when {
                    downloadedCount == 0 -> "No files were saved on this device."
                    downloadedCount == files.size -> "Saved $downloadedCount file(s). You can open them below."
                    else -> "Saved $downloadedCount of ${files.size} file(s)."
                },
                files = files
            )
        )
    }

    fun showNotReceived(reason: String) {
        showResult(
            ReceiveResultUi(
                received = false,
                title = "Nothing received",
                message = reason,
                files = emptyList()
            )
        )
    }

    LaunchedEffect(signalState) {
        when (val s = signalState) {
            is PairingSignalState.Confirming -> {
                if (phase != ReceivePhase.Result && phase != ReceivePhase.Transferring) {
                    phase = ReceivePhase.Confirming
                }
                // Prepare disk sink early so host chunks aren't dropped after confirm.
                if (remoteFiles.isNotEmpty()) {
                    signaling.prepareGuestFileSink(
                        context = context,
                        expected = remoteFiles,
                        encryptFileTransfer = encryptFileTransfer,
                        beginTransfer = false
                    )
                }
            }
            is PairingSignalState.Paired -> {
                if (phase != ReceivePhase.Result) {
                    val shouldStart = phase != ReceivePhase.Transferring
                    phase = ReceivePhase.Transferring
                    if (shouldStart) {
                        val expected = remoteFiles.ifEmpty { emptyList() }
                        signaling.prepareGuestFileSink(
                            context = context,
                            expected = expected,
                            encryptFileTransfer = encryptFileTransfer,
                            beginTransfer = true
                        )
                    }
                }
            }
            is PairingSignalState.Connecting, is PairingSignalState.Waiting -> {
                if (phase == ReceivePhase.EnterCode) phase = ReceivePhase.Connecting
            }
            is PairingSignalState.Failed -> {
                if (phase != ReceivePhase.Result) {
                    showNotReceived(s.reason)
                }
            }
            else -> Unit
        }
        // #region agent log
        AgentDebugLog.log(
            hypothesisId = "H-SIGNAL",
            location = "ReceivePlaceholderScreen.signalState",
            message = "guest signal state",
            data = mapOf(
                "state" to signalState.toString(),
                "phase" to phase.name,
                "remoteFileCount" to remoteFiles.size
            ),
            runId = "post-fix"
        )
        // #endregion
    }

    // If manifest arrives after we already entered Transferring, (re)prepare sink metadata.
    LaunchedEffect(remoteFiles, phase) {
        if (phase != ReceivePhase.Transferring) return@LaunchedEffect
        if (remoteFiles.isEmpty()) return@LaunchedEffect
        signaling.prepareGuestFileSink(
            context = context,
            expected = remoteFiles,
            encryptFileTransfer = encryptFileTransfer,
            beginTransfer = false
        )
    }

    LaunchedEffect(transferComplete, transferFailed, savedFiles, phase) {
        if (phase != ReceivePhase.Transferring) return@LaunchedEffect
        transferFailed?.let {
            showNotReceived(it)
            return@LaunchedEffect
        }
        if (transferComplete) {
            val files = savedFiles.ifEmpty {
                remoteFiles.map {
                    ReceivedFileRecord(it.name, it.sizeBytes, downloaded = false, localUri = null)
                }
            }
            showReceived(files)
        }
    }

    LaunchedEffect(phase) {
        if (phase == ReceivePhase.Transferring) {
            InterstitialAds.prefetch()
        }
    }

    LaunchedEffect(phase, result?.received) {
        if (phase != ReceivePhase.Result || result?.received != true) return@LaunchedEffect
        context.findActivity()?.let { activity ->
            InterstitialAds.showAfterTransfer(activity)
        }
    }

    // If files landed but the complete flag was missed, still leave the transferring screen.
    LaunchedEffect(savedFiles, phase) {
        if (phase != ReceivePhase.Transferring) return@LaunchedEffect
        if (transferComplete || transferFailed != null) return@LaunchedEffect
        if (savedFiles.any { it.downloaded } &&
            remoteFiles.isNotEmpty() &&
            savedFiles.count { it.downloaded } >= remoteFiles.size
        ) {
            showReceived(savedFiles)
        }
    }

    val headerState: SessionUiState = when {
        phase == ReceivePhase.Result && result?.received == true -> SessionUiState.Completed
        phase == ReceivePhase.Result -> SessionUiState.Failed(
            diagnosis = result?.message ?: "Not received",
            actions = listOf("Dismiss and try again")
        )
        signalState is PairingSignalState.Confirming ->
            SessionUiState.ConfirmDevices((signalState as PairingSignalState.Confirming).phrase)
        else -> when (phase) {
            ReceivePhase.EnterCode -> SessionUiState.EnterOfferCode
            ReceivePhase.Connecting -> SessionUiState.Connecting(
                strategyLabel = "Internet pairing",
                detail = "Pairing with the sharer over the internet…"
            )
            ReceivePhase.Confirming -> SessionUiState.ConfirmDevices(
                (signalState as? PairingSignalState.Confirming)?.phrase ?: "…"
            )
            ReceivePhase.Transferring -> SessionUiState.Transferring(
                transferProgress ?: TransferProgressUi(
                    sending = false,
                    bytesDone = 0,
                    bytesTotal = 1,
                    currentFileName = "Starting receive…",
                    currentFileDone = 0,
                    currentFileTotal = 1,
                    speedBytesPerSec = 0,
                    etaSeconds = null
                )
            )
            ReceivePhase.Result -> SessionUiState.Completed
        }
    }

    val step = when (phase) {
        ReceivePhase.EnterCode -> ConnectStep.CodeExchanged
        ReceivePhase.Connecting -> ConnectStep.Checking
        ReceivePhase.Confirming -> ConnectStep.Checking
        ReceivePhase.Transferring -> ConnectStep.Connected
        ReceivePhase.Result -> ConnectStep.Connected
    }

    fun submit() {
        if (!networkAllowed) {
            Toast.makeText(
                context,
                "Enable “Use mobile data” or connect to Wi‑Fi",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val trimmed = PairingCode.normalize(code)
        if (!PairingCode.isValidShort(trimmed)) {
            Toast.makeText(
                context,
                "Enter the full ${PairingCode.TOTAL_LENGTH}-character share code (${PairingCode.LETTER_COUNT} letters + ${PairingCode.DIGIT_COUNT} digits)",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        result = null
        phase = ReceivePhase.Connecting
        signaling.startGuest(trimmed)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Receive") },
                navigationIcon = {
                    IconButton(onClick = { dismissAndLeave() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
        ) {
            if (phase != ReceivePhase.Result) {
                SessionStatusHeader(state = headerState)
                Spacer(modifier = Modifier.height(16.dp))
                MobileDataNetworkControls(
                    snapshot = snapshot,
                    useMobileData = useMobileData,
                    onUseMobileDataChange = onUseMobileDataChange
                )
                Spacer(modifier = Modifier.height(16.dp))
                ConnectStepper(current = step)
                Spacer(modifier = Modifier.height(24.dp))
            }

            when (phase) {
                ReceivePhase.EnterCode -> {
                    PairingCodeField(
                        value = code,
                        onValueChange = { code = it },
                        onDone = { submit() }
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = { submit() },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = PairingCode.isValidShort(code)
                    ) { Text("Pair") }
                    TextButton(
                        onClick = {
                            Toast.makeText(context, "QR scan will be optional later", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Scan QR instead") }
                }

                ReceivePhase.Connecting -> {
                    Text("Connecting over the internet…", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(12.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "No file picking on this side — after pairing you’ll see what the sharer sent.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                    )
                    if (remoteFiles.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(16.dp))
                        ReceivedFilesList(remoteFiles)
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                    OutlinedButton(
                        onClick = { cancelAttempt() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Cancel and re-enter code")
                    }
                }

                ReceivePhase.Confirming -> {
                    when (val s = signalState) {
                        is PairingSignalState.Confirming -> {
                            PairingConfirmCard(
                                confirming = s,
                                onConfirm = { signaling.confirmLocalPairing() },
                                onReject = {
                                    signaling.rejectLocalPairing()
                                    cancelAttempt()
                                }
                            )
                        }
                        else -> Text("Preparing confirmation…")
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedButton(
                        onClick = { cancelAttempt() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Cancel and re-enter code")
                    }
                }

                ReceivePhase.Transferring -> {
                    Text(
                        text = "Receiving…",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Transfer started automatically after both devices confirmed.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    transferProgress?.let { TransferProgressPanel(progress = it) }
                        ?: LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(24.dp))
                    OutlinedButton(
                        onClick = { cancelAttempt() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Cancel and re-enter code")
                    }
                }

                ReceivePhase.Result -> {
                    val outcome = result ?: ReceiveResultUi(
                        received = false,
                        title = "Nothing received",
                        message = "Unknown result"
                    )
                    ReceiveResultCard(
                        result = outcome,
                        onOpenFile = { file ->
                            ReceivedFileOpener.open(context, file.name, file.localUri)
                        },
                        onDismiss = { dismissAndLeave() }
                    )
                }
            }
        }
    }
}

@Composable
private fun ReceivedFilesList(files: List<SharedFileInfo>) {
    Text(
        text = "${files.size} file(s) listed",
        style = MaterialTheme.typography.titleSmall
    )
    Spacer(modifier = Modifier.height(8.dp))
    files.forEach { file ->
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text(
                text = file.name,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium
            )
            if (file.sizeBytes >= 0) {
                Text(
                    text = formatBytes(file.sizeBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                )
            }
        }
    }
}
