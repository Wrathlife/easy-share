package com.easyshare.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.easyshare.app.ui.components.SessionStatusHeader
import com.easyshare.app.ui.state.SessionUiState

@Composable
fun HomeScreen(
    onShare: () -> Unit,
    onReceive: () -> Unit,
    onPreviewProgress: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Easy Share",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        SessionStatusHeader(state = SessionUiState.Idle)
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = onShare,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Share files")
        }
        OutlinedButton(
            onClick = onReceive,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Receive files")
        }
        TextButton(onClick = onPreviewProgress) {
            Text("Preview transfer UI")
        }
    }
}
