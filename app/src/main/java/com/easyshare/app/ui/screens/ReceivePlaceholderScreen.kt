package com.easyshare.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.easyshare.app.ui.components.ConnectStepper
import com.easyshare.app.ui.components.SessionStatusHeader
import com.easyshare.app.ui.state.ConnectStep
import com.easyshare.app.ui.state.SessionUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceivePlaceholderScreen(onBack: () -> Unit) {
    val state = SessionUiState.ScanOfferQr
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Receive") },
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
            Spacer(modifier = Modifier.height(16.dp))
            ConnectStepper(current = ConnectStep.QrExchanged)
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "Camera QR scan lands here next. After scanning the offer, you’ll show an answer QR for the sharer.",
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}
