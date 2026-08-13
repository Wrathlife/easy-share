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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.netshare.app.ui.state.TransferProgressUi

@Composable
fun ShareResultCard(
    itemCount: Int,
    progress: TransferProgressUi?,
    onDismiss: () -> Unit
) {
    val container = MaterialTheme.colorScheme.primaryContainer
    val onContainer = MaterialTheme.colorScheme.onPrimaryContainer

    Column(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(container)
                .padding(horizontal = 20.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "SENT",
                style = MaterialTheme.typography.labelMedium,
                color = onContainer.copy(alpha = 0.75f)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = if (itemCount == 1) "File sent" else "Files sent",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = onContainer
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Finished sending $itemCount item(s).",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = onContainer.copy(alpha = 0.9f)
            )
        }

        progress?.let {
            Spacer(modifier = Modifier.height(20.dp))
            TransferProgressPanel(progress = it)
        }

        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Dismiss")
        }
    }
}
