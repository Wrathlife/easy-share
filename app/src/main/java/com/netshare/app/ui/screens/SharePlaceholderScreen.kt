package com.netshare.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.netshare.app.ads.InterstitialAds
import com.netshare.app.ads.findActivity
import com.netshare.app.connect.ConnectivitySnapshot
import com.netshare.app.connect.canUseCurrentNetwork
import com.netshare.app.debug.AgentDebugLog
import com.netshare.app.files.LocalShareEntry
import com.netshare.app.files.SafShareCollector
import com.netshare.app.permissions.rememberNotificationPermissionRequester
import com.netshare.app.signaling.InternetCodeSignaling
import com.netshare.app.signaling.PairingSignalState
import com.netshare.app.signaling.SharedFileInfo
import com.netshare.app.ui.components.ConnectStepper
import com.netshare.app.ui.components.MobileDataNetworkControls
import com.netshare.app.ui.components.PairingConfirmCard
import com.netshare.app.ui.components.SessionStatusHeader
import com.netshare.app.ui.components.ShareResultCard
import com.netshare.app.ui.components.TransferProgressPanel
import com.netshare.app.ui.components.formatBytes
import com.netshare.app.ui.state.ConnectStep
import com.netshare.app.ui.state.QueueItemStatus
import com.netshare.app.ui.state.SessionUiState
import com.netshare.app.ui.state.TransferProgressUi
import com.netshare.app.ui.state.TransferQueueItemUi
import com.netshare.app.webrtc.PairingCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class SharePhase { PickFiles, WaitingForPeer, Confirming, Transferring, Completed }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharePlaceholderScreen(
    snapshot: ConnectivitySnapshot,
    useMobileData: Boolean,
    onUseMobileDataChange: (Boolean) -> Unit,
    encryptFileTransfer: Boolean = false,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val collector = remember { SafShareCollector(context) }
    val signaling = remember { InternetCodeSignaling(scope) }
    val signalState by signaling.state.collectAsState()
    var phase by remember { mutableStateOf(SharePhase.PickFiles) }
    var entries by remember { mutableStateOf<List<LocalShareEntry>>(emptyList()) }
    var scanningFolder by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf(PairingCode.generateShort()) }
    val transferProgress by signaling.transferProgress.collectAsState()
    val transferComplete by signaling.transferComplete.collectAsState()
    val transferFailed by signaling.transferFailed.collectAsState()
    val networkAllowed = canUseCurrentNetwork(snapshot, useMobileData)
    val requestNotifications = rememberNotificationPermissionRequester()

    fun leaveToHome() {
        signaling.stop()
        phase = SharePhase.PickFiles
        onBack()
    }

    BackHandler { leaveToHome() }

    DisposableEffect(Unit) {
        onDispose { signaling.stop() }
    }

    LaunchedEffect(signalState) {
        when (val s = signalState) {
            is PairingSignalState.Confirming -> {
                if (phase != SharePhase.Completed && phase != SharePhase.Transferring) {
                    phase = SharePhase.Confirming
                }
            }
            is PairingSignalState.Paired -> {
                if (phase == SharePhase.Confirming || phase == SharePhase.WaitingForPeer) {
                    phase = SharePhase.Transferring
                    signaling.startHostFileTransfer(
                        context = context,
                        entries = entries,
                        encryptFileTransfer = encryptFileTransfer
                    )
                }
            }
            is PairingSignalState.Failed -> {
                if (phase == SharePhase.Confirming || phase == SharePhase.WaitingForPeer || phase == SharePhase.Transferring) {
                    Toast.makeText(context, s.reason, Toast.LENGTH_LONG).show()
                }
            }
            else -> Unit
        }
        // #region agent log
        AgentDebugLog.log(
            hypothesisId = "H-SIGNAL",
            location = "SharePlaceholderScreen.signalState",
            message = "host signal state",
            data = mapOf("state" to signalState.toString(), "phase" to phase.name),
            runId = "post-fix"
        )
        // #endregion
    }

    LaunchedEffect(phase) {
        if (phase == SharePhase.Transferring) {
            InterstitialAds.prefetch()
        }
    }

    LaunchedEffect(transferComplete, transferFailed, phase) {
        if (phase != SharePhase.Transferring) return@LaunchedEffect
        transferFailed?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            return@LaunchedEffect
        }
        if (transferComplete) {
            phase = SharePhase.Completed
            context.findActivity()?.let { activity ->
                InterstitialAds.showAfterTransfer(activity)
            }
        }
    }

    val pickFiles = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        uris.forEach { uri ->
            collector.takePersistableRead(uri)
        }
        val collected = collector.collectFiles(uris)
        entries = collector.mergeUnique(entries, collected)
        if (collected.isEmpty()) {
            Toast.makeText(context, "Could not read selected files", Toast.LENGTH_SHORT).show()
        }
    }

    val pickFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { treeUri: Uri? ->
        if (treeUri == null) return@rememberLauncherForActivityResult
        scanningFolder = true
        scope.launch {
            try {
                val collected = withContext(Dispatchers.IO) {
                    // Result Intent flags aren't available from OpenDocumentTree — READ is enough.
                    collector.takePersistableRead(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    collector.collectTree(treeUri)
                }
                if (collected.isEmpty()) {
                    Toast.makeText(
                        context,
                        "No files found in that folder (empty or inaccessible)",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    entries = collector.mergeUnique(entries, collected)
                    Toast.makeText(
                        context,
                        "Added ${collected.size} file(s) from folder",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (t: Throwable) {
                Toast.makeText(
                    context,
                    "Couldn’t read folder: ${t.message ?: t.javaClass.simpleName}",
                    Toast.LENGTH_LONG
                ).show()
                AgentDebugLog.log(
                    hypothesisId = "H-FOLDER",
                    location = "SharePlaceholderScreen.pickFolder",
                    message = "folder collect crashed",
                    data = mapOf("error" to (t.message ?: t.toString())),
                    runId = "folder-crash-fix"
                )
            } finally {
                scanningFolder = false
            }
        }
    }

    val headerState: SessionUiState = when (val s = signalState) {
        is PairingSignalState.Confirming -> SessionUiState.ConfirmDevices(s.phrase)
        else -> when (phase) {
            SharePhase.PickFiles -> SessionUiState.ChooseFilesToShare
            SharePhase.WaitingForPeer -> SessionUiState.ShowOfferCode(code = code, strategyLabel = "Internet pairing")
            SharePhase.Confirming -> SessionUiState.ConfirmDevices(
                (signalState as? PairingSignalState.Confirming)?.phrase ?: "…"
            )
            SharePhase.Transferring -> SessionUiState.Transferring(
                transferProgress ?: TransferProgressUi(
                    sending = true,
                    bytesDone = 0,
                    bytesTotal = entries.sumOf { it.sizeBytes.coerceAtLeast(1L) }.coerceAtLeast(1L),
                    currentFileName = entries.firstOrNull()?.relativePath,
                    currentFileDone = 0,
                    currentFileTotal = entries.firstOrNull()?.sizeBytes?.coerceAtLeast(1L) ?: 1L,
                    speedBytesPerSec = 0,
                    etaSeconds = null,
                    queue = entries.map { TransferQueueItemUi(it.relativePath, QueueItemStatus.Waiting) }
                )
            )
            SharePhase.Completed -> SessionUiState.Completed
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Share") },
                navigationIcon = {
                    IconButton(onClick = { leaveToHome() }) {
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
            if (phase != SharePhase.Completed) {
                SessionStatusHeader(
                    state = headerState,
                    strategyLabel = if (phase != SharePhase.PickFiles) "Internet pairing" else null
                )
                Spacer(modifier = Modifier.height(16.dp))
                MobileDataNetworkControls(
                    snapshot = snapshot,
                    useMobileData = useMobileData,
                    onUseMobileDataChange = onUseMobileDataChange
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            when (phase) {
                SharePhase.PickFiles -> {
                    Button(
                        onClick = {
                            runCatching { pickFiles.launch(arrayOf("*/*")) }
                                .onFailure { err ->
                                    Toast.makeText(
                                        context,
                                        "Couldn’t open file picker: ${err.message ?: err.javaClass.simpleName}",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !scanningFolder
                    ) { Text("Add files") }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            runCatching { pickFolder.launch(null) }
                                .onFailure {
                                    Toast.makeText(
                                        context,
                                        "Folder picker isn’t available on this device",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !scanningFolder
                    ) { Text(if (scanningFolder) "Scanning…" else "Add folder") }

                    if (scanningFolder) {
                        Spacer(modifier = Modifier.height(12.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Scanning folder…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (entries.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("${entries.size} item(s) selected", style = MaterialTheme.typography.titleSmall)
                        Spacer(modifier = Modifier.height(8.dp))
                        entries.take(30).forEach { entry ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (entries.size > 1) {
                                    IconButton(
                                        onClick = {
                                            entries = entries.filterNot {
                                                it.uri == entry.uri && it.relativePath == entry.relativePath
                                            }
                                        },
                                        enabled = !scanningFolder,
                                        modifier = Modifier.size(40.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Close,
                                            contentDescription = "Remove ${entry.displayName}",
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                                Text(
                                    text = entry.relativePath,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                if (entry.sizeBytes >= 0) {
                                    Text(
                                        text = formatBytes(entry.sizeBytes),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                                    )
                                }
                            }
                        }
                        if (entries.size > 30) {
                            Text(
                                text = "…and ${entries.size - 30} more",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(
                            onClick = { entries = emptyList() },
                            enabled = !scanningFolder
                        ) { Text("Clear selection") }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = {
                            if (entries.isEmpty()) {
                                Toast.makeText(context, "Pick at least one file or folder first", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            if (entries.size > InternetCodeSignaling.MAX_MANIFEST_FILES) {
                                Toast.makeText(
                                    context,
                                    "Too many files (${entries.size}). Max ${InternetCodeSignaling.MAX_MANIFEST_FILES} per share.",
                                    Toast.LENGTH_LONG
                                ).show()
                                return@Button
                            }
                            if (!networkAllowed) {
                                Toast.makeText(context, "Enable “Use mobile data” or connect to Wi‑Fi", Toast.LENGTH_LONG).show()
                                return@Button
                            }
                            requestNotifications()
                            phase = SharePhase.WaitingForPeer
                            signaling.startHost(
                                code,
                                entries.map {
                                    SharedFileInfo(
                                        name = it.relativePath,
                                        sizeBytes = it.sizeBytes
                                    )
                                }
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = entries.isNotEmpty() && !scanningFolder
                    ) { Text("Create share code") }
                }

                SharePhase.WaitingForPeer -> {
                    ConnectStepper(
                        current = when (signalState) {
                            is PairingSignalState.Connecting -> ConnectStep.Gathering
                            is PairingSignalState.Waiting -> ConnectStep.Checking
                            is PairingSignalState.Paired -> ConnectStep.Connected
                            else -> ConnectStep.CodeExchanged
                        }
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Sharing ${entries.size} item(s)", style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(16.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .padding(horizontal = 20.dp, vertical = 28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "SHARE CODE",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = PairingCode.formatForDisplay(code),
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 28.sp,
                            letterSpacing = 1.5.sp,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            softWrap = false,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = { copyToClipboard(context, code) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Copy code") }
                    Spacer(modifier = Modifier.height(20.dp))
                    when (val s = signalState) {
                        is PairingSignalState.Failed -> Text(
                            text = "Pairing signal error: ${s.reason}",
                            color = MaterialTheme.colorScheme.error
                        )
                        else -> {
                            Text("Waiting for the other device…", style = MaterialTheme.typography.titleSmall)
                            Spacer(modifier = Modifier.height(8.dp))
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                    TextButton(onClick = {
                        signaling.stop()
                        code = PairingCode.generateShort()
                        phase = SharePhase.PickFiles
                    }) { Text("Change files") }
                }

                SharePhase.Confirming -> {
                    ConnectStepper(current = ConnectStep.Checking)
                    Spacer(modifier = Modifier.height(16.dp))
                    when (val s = signalState) {
                        is PairingSignalState.Confirming -> {
                            PairingConfirmCard(
                                confirming = s,
                                onConfirm = { signaling.confirmLocalPairing() },
                                onReject = {
                                    signaling.rejectLocalPairing()
                                    phase = SharePhase.WaitingForPeer
                                }
                            )
                        }
                        else -> Text("Preparing confirmation…")
                    }
                }

                SharePhase.Transferring -> {
                    ConnectStepper(current = ConnectStep.Connected)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Sending…",
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
                }

                SharePhase.Completed -> {
                    ShareResultCard(
                        itemCount = entries.size,
                        progress = transferProgress,
                        onDismiss = { leaveToHome() }
                    )
                }
            }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Netshare code", text))
    Toast.makeText(context, "Code copied", Toast.LENGTH_SHORT).show()
}
