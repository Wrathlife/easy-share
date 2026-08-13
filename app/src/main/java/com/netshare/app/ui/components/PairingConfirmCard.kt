package com.netshare.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.netshare.app.signaling.PairingSignalState

@Composable
fun PairingConfirmCard(
    confirming: PairingSignalState.Confirming,
    onConfirm: () -> Unit,
    onReject: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Confirm these are the right devices",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Both screens should show the same two words. Read them aloud, then confirm.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(16.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .padding(horizontal = 20.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "MATCH WORDS",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = confirming.phrase,
                fontWeight = FontWeight.Bold,
                fontSize = 28.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = when {
                confirming.localConfirmed && confirming.peerConfirmed -> "Both confirmed"
                confirming.localConfirmed -> "Waiting for the other device to confirm…"
                confirming.peerConfirmed -> "Other device confirmed — your turn"
                else -> "Neither device has confirmed yet"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = onConfirm,
            modifier = Modifier.fillMaxWidth(),
            enabled = !confirming.localConfirmed
        ) {
            Text(if (confirming.localConfirmed) "You confirmed" else "Yes — words match")
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            onClick = onReject,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("No — cancel pairing")
        }
    }
}
