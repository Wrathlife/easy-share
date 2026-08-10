package com.easyshare.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.easyshare.app.ui.components.SessionStatusHeader
import com.easyshare.app.ui.components.TransferProgressPanel
import com.easyshare.app.ui.state.QueueItemStatus
import com.easyshare.app.ui.state.SessionUiState
import com.easyshare.app.ui.state.TransferProgressUi
import com.easyshare.app.ui.state.TransferQueueItemUi

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreviewTransferScreen(onBack: () -> Unit) {
    val progress = TransferProgressUi(
        sending = true,
        bytesDone = 42_000_000L,
        bytesTotal = 80_000_000L,
        currentFileName = "vacation-reel.mp4",
        currentFileDone = 12_000_000L,
        currentFileTotal = 30_000_000L,
        speedBytesPerSec = 2_400_000L,
        etaSeconds = 16,
        queue = listOf(
            TransferQueueItemUi("notes.pdf", QueueItemStatus.Done),
            TransferQueueItemUi("vacation-reel.mp4", QueueItemStatus.Active),
            TransferQueueItemUi("photos.zip", QueueItemStatus.Waiting)
        )
    )
    val state = SessionUiState.Transferring(progress)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Transfer") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
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
                .padding(24.dp)
        ) {
            SessionStatusHeader(state = state)
            Spacer(modifier = Modifier.height(20.dp))
            TransferProgressPanel(progress = progress)
            Spacer(modifier = Modifier.height(24.dp))
            OutlinedButton(onClick = onBack) {
                Text("Cancel")
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = onBack) {
                Text("Done")
            }
        }
    }
}
