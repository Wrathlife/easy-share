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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.netshare.app.files.ReceivedFileOpener
import com.netshare.app.history.ReceivedFileRecord
import com.netshare.app.history.ReceivedHistoryStore
import com.netshare.app.history.ReceivedSessionRecord
import com.netshare.app.ui.components.formatBytes
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceivedHistoryScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val store = remember { ReceivedHistoryStore(context) }
    var sessions by remember { mutableStateOf(store.list()) }
    val dateFormat = remember {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    }

    fun refresh() {
        sessions = store.list()
    }

    BackHandler { onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Received history") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (sessions.isNotEmpty()) {
                        TextButton(onClick = {
                            store.clear()
                            refresh()
                        }) { Text("Clear") }
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
            if (sessions.isEmpty()) {
                Text(
                    text = "No received shares yet. After you pair and get a file list, it will show up here.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                )
            } else {
                sessions.forEach { session ->
                    HistorySessionCard(
                        session = session,
                        dateFormat = dateFormat,
                        onOpen = { file ->
                            ReceivedFileOpener.open(context, file.name, file.localUri)
                        }
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
private fun HistorySessionCard(
    session: ReceivedSessionRecord,
    dateFormat: DateFormat,
    onOpen: (ReceivedFileRecord) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = dateFormat.format(Date(session.receivedAtEpochMs)),
            style = MaterialTheme.typography.titleSmall
        )
        Text(
            text = "Code ${session.shareCode} · ${session.fileCount} file(s)" +
                if (session.downloadedCount > 0) " · ${session.downloadedCount} downloaded" else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
        Spacer(modifier = Modifier.height(8.dp))
        session.files.forEach { file ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = file.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = buildString {
                            if (file.sizeBytes >= 0) append(formatBytes(file.sizeBytes))
                            append(if (file.downloaded) " · saved" else " · listed")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                    )
                }
                TextButton(onClick = { onOpen(file) }) { Text("Open") }
            }
        }
    }
}
