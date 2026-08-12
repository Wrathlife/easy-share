package com.easyshare.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.easyshare.app.connect.ConnectivitySnapshot
import com.easyshare.app.ui.components.MobileDataNetworkControls
import com.easyshare.app.ui.components.SessionStatusHeader
import com.easyshare.app.ui.state.SessionUiState

@Composable
fun HomeScreen(
    snapshot: ConnectivitySnapshot,
    useMobileData: Boolean,
    onUseMobileDataChange: (Boolean) -> Unit,
    networkAllowed: Boolean,
    onShare: () -> Unit,
    onReceive: () -> Unit,
    onReceivedHistory: () -> Unit,
    onPreviewProgress: () -> Unit
) {
    val context = LocalContext.current

    fun guard(action: () -> Unit) {
        if (!networkAllowed) {
            Toast.makeText(
                context,
                "Mobile data only — enable “Use mobile data” or connect to Wi‑Fi",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        action()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Easy Share",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        SessionStatusHeader(state = SessionUiState.Idle)
        MobileDataNetworkControls(
            snapshot = snapshot,
            useMobileData = useMobileData,
            onUseMobileDataChange = onUseMobileDataChange
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = { guard(onShare) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Share files")
        }
        OutlinedButton(
            onClick = { guard(onReceive) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Receive files")
        }
        OutlinedButton(
            onClick = onReceivedHistory,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Received history")
        }
        TextButton(onClick = onPreviewProgress) {
            Text("Preview transfer UI")
        }
    }
}
