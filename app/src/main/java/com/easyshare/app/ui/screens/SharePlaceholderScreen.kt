package com.easyshare.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.easyshare.app.connect.ConnectivitySnapshot
import com.easyshare.app.connect.canUseCurrentNetwork
import com.easyshare.app.debug.AgentDebugLog
import com.easyshare.app.files.LocalShareEntry
import com.easyshare.app.files.SafShareCollector
import com.easyshare.app.permissions.RequestNotificationPermissionIfNeeded
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

private enum class SharePhase { PickFiles, WaitingForPeer, Paired }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharePlaceholderScreen(
    snapshot: ConnectivitySnapshot,
    useMobileData: Boolean,
    onUseMobileDataChange: (Boolean) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val collector = remember { SafShareCollector(context) }
    val signaling = remember { InternetCodeSignaling(scope) }
    val signalState by signaling.state.collectAsState()

    var phase by remember { mutableStateOf(SharePhase.PickFiles) }
    var entries by remember { mutableStateOf<List<LocalShareEntry>>(emptyList()) }
    var code by remember { mutableStateOf(PairingCode.generateShort()) }
    val networkAllowed = canUseCurrentNetwork(snapshot, useMobileData)

    RequestNotificationPermissionIfNeeded()

    DisposableEffect(Unit) {
        onDispose { signaling.stop() }
    }

    LaunchedEffect(signalState) {
        if (signalState is PairingSignalState.Paired) {
            phase = SharePhase.Paired
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

    val pickFiles = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        uris.forEach { uri ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
        val collected = collector.collectFiles(uris)
        entries = collector.mergeUnique(entries, collected)
    }

    val pickFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { treeUri: Uri? ->
        if (treeUri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        val collected = collector.collectTree(treeUri)
        entries = collector.mergeUnique(entries, collected)
    }

    val headerState: SessionUiState = when (phase) {
        SharePhase.PickFiles -> SessionUiState.ChooseFilesToShare
        SharePhase.WaitingForPeer -> SessionUiState.ShowOfferCode(code = code, strategyLabel = "Internet pairing")
        SharePhase.Paired -> SessionUiState.HostPaired
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Share") },
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

            when (phase) {
                SharePhase.PickFiles -> {
                    Button(
                        onClick = { pickFiles.launch(arrayOf("*/*")) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Add files") }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { pickFolder.launch(null) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Add folder") }

                    if (entries.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("${entries.size} item(s) selected", style = MaterialTheme.typography.titleSmall)
                        Spacer(modifier = Modifier.height(8.dp))
                        entries.take(30).forEach { entry ->
                            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
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
                        TextButton(onClick = { entries = emptyList() }) { Text("Clear selection") }
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
                        enabled = entries.isNotEmpty()
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
                            text = code,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 32.sp,
                            letterSpacing = 3.sp,
                            textAlign = TextAlign.Center,
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

                SharePhase.Paired -> {
                    ConnectStepper(current = ConnectStep.Connected)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Paired",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "The receiver joined with your code. File transfer comes next — ${entries.size} item(s) ready.",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = {
                        signaling.stop()
                        onBack()
                    }, modifier = Modifier.fillMaxWidth()) { Text("Done") }
                }
            }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Easy Share code", text))
    Toast.makeText(context, "Code copied", Toast.LENGTH_SHORT).show()
}
