package com.easyshare.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.easyshare.app.connect.ConnectivitySnapshot
import com.easyshare.app.connect.canUseCurrentNetwork
import com.easyshare.app.debug.AgentDebugLog
import com.easyshare.app.history.ReceivedFileRecord
import com.easyshare.app.history.ReceivedHistoryStore
import com.easyshare.app.history.ReceivedSessionRecord
import com.easyshare.app.signaling.InternetCodeSignaling
import com.easyshare.app.signaling.PairingSignalState
import com.easyshare.app.signaling.SharedFileInfo
import com.easyshare.app.ui.components.ConnectStepper
import com.easyshare.app.ui.components.MobileDataNetworkControls
import com.easyshare.app.ui.components.SessionStatusHeader
import com.easyshare.app.ui.components.formatBytes
import com.easyshare.app.ui.state.ConnectStep
import com.easyshare.app.ui.state.SessionUiState
import com.easyshare.app.webrtc.PairingCode

private enum class ReceivePhase { EnterCode, Connecting, Paired }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceivePlaceholderScreen(
    snapshot: ConnectivitySnapshot,
    useMobileData: Boolean,
    onUseMobileDataChange: (Boolean) -> Unit,
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
    val networkAllowed = canUseCurrentNetwork(snapshot, useMobileData)

    DisposableEffect(Unit) {
        onDispose { signaling.stop() }
    }

    LaunchedEffect(signalState) {
        when (val s = signalState) {
            is PairingSignalState.Paired -> phase = ReceivePhase.Paired
            is PairingSignalState.Connecting, is PairingSignalState.Waiting -> {
                if (phase == ReceivePhase.EnterCode) phase = ReceivePhase.Connecting
            }
            is PairingSignalState.Failed -> {
                Toast.makeText(context, s.reason, Toast.LENGTH_LONG).show()
                phase = ReceivePhase.EnterCode
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
                "remoteFileCount" to remoteFiles.size,
                "header" to when (phase) {
                    ReceivePhase.Paired -> "WaitingForSharedFiles"
                    ReceivePhase.Connecting -> "Connecting"
                    ReceivePhase.EnterCode -> "EnterOfferCode"
                }
            ),
            runId = "post-fix"
        )
        // #endregion
    }

    LaunchedEffect(remoteFiles, phase) {
        if (phase != ReceivePhase.Paired) return@LaunchedEffect
        if (remoteFiles.isEmpty()) return@LaunchedEffect
        val normalized = PairingCode.normalize(code)
        if (!PairingCode.isValidShort(normalized)) return@LaunchedEffect
        history.upsertSession(
            ReceivedSessionRecord(
                id = com.easyshare.app.signaling.SignalingCrypto.topicId(normalized),
                shareCode = ReceivedHistoryStore.redactShareCode(normalized),
                receivedAtEpochMs = System.currentTimeMillis(),
                files = remoteFiles.map {
                    ReceivedFileRecord(
                        name = it.name,
                        sizeBytes = it.sizeBytes,
                        downloaded = false
                    )
                }
            )
        )
        if (!history.encryptionAvailable && !warnedHistoryUnavailable) {
            warnedHistoryUnavailable = true
            Toast.makeText(
                context,
                "Received list shown, but encrypted history storage is unavailable on this device",
                Toast.LENGTH_LONG
            ).show()
        }
        // #region agent log
        AgentDebugLog.log(
            hypothesisId = "H5",
            location = "ReceivePlaceholderScreen.remoteFiles",
            message = "persisted verified share manifest",
            data = mapOf(
                "codeLen" to normalized.length,
                "fileCount" to remoteFiles.size,
                "phase" to phase.name
            ),
            runId = "fix-all"
        )
        // #endregion
    }

    val headerState: SessionUiState = when (phase) {
        ReceivePhase.EnterCode -> SessionUiState.EnterOfferCode
        ReceivePhase.Connecting -> SessionUiState.Connecting(
            strategyLabel = "Internet pairing",
            detail = "Pairing with the sharer over the internet…"
        )
        // IMPORTANT: do NOT use ConnectedBrowsing here — that subtitle told users to pick downloads.
        ReceivePhase.Paired -> SessionUiState.WaitingForSharedFiles
    }

    val step = when (phase) {
        ReceivePhase.EnterCode -> ConnectStep.CodeExchanged
        ReceivePhase.Connecting -> ConnectStep.Checking
        ReceivePhase.Paired -> ConnectStep.Connected
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
        // #region agent log
        AgentDebugLog.log(
            hypothesisId = "H3",
            location = "ReceivePlaceholderScreen.submit",
            message = "guest submitted valid share code",
            data = mapOf(
                "codeLen" to trimmed.length,
                "signalChannel" to "mqtts"
            ),
            runId = "fix-all"
        )
        // #endregion
        phase = ReceivePhase.Connecting
        signaling.startGuest(trimmed)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Receive") },
                navigationIcon = {
                    IconButton(onClick = {
                        signaling.stop()
                        onBack()
                    }) {
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

            when (phase) {
                ReceivePhase.EnterCode -> {
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it.uppercase().filter { ch -> ch.isLetterOrDigit() } },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Share code") },
                        placeholder = { Text("ABCDFGHJ23456789") },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = FontFamily.Monospace
                        ),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Characters,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(onDone = { submit() })
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
                }

                ReceivePhase.Paired -> {
                    Text(
                        text = "Paired",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    if (remoteFiles.isEmpty()) {
                        Text(
                            text = "Waiting for the sharer’s file list… Download comes next.",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else {
                        Text(
                            text = "Sharer offered these files (bytes still P2P — listed for now):",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        ReceivedFilesList(remoteFiles)
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = {
                            signaling.stop()
                            onBack()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Done") }
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
