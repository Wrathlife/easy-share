package com.netshare.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.netshare.app.history.ReceivedFileRecord
import com.netshare.app.ui.components.formatBytes

data class ReceiveResultUi(
    val received: Boolean,
    val title: String,
    val message: String,
    val files: List<ReceivedFileRecord> = emptyList()
)

@Composable
fun ReceiveResultCard(
    result: ReceiveResultUi,
    onOpenFile: (ReceivedFileRecord) -> Unit,
    onDismiss: () -> Unit
) {
    val container = if (result.received) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.errorContainer
    }
    val onContainer = if (result.received) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onErrorContainer
    }

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
                text = if (result.received) "RECEIVED" else "NOT RECEIVED",
                style = MaterialTheme.typography.labelMedium,
                color = onContainer.copy(alpha = 0.75f)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = result.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = onContainer
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = result.message,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = onContainer.copy(alpha = 0.9f)
            )
        }

        if (result.files.isNotEmpty()) {
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = "${result.files.size} file(s)",
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(modifier = Modifier.height(8.dp))
            result.files.forEach { file ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = file.name,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = buildString {
                                if (file.sizeBytes >= 0) append(formatBytes(file.sizeBytes))
                                append(
                                    when {
                                        file.downloaded -> " · saved on device"
                                        else -> " · listed (bytes not downloaded yet)"
                                    }
                                )
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                        )
                    }
                    TextButton(onClick = { onOpenFile(file) }) {
                        Text(if (file.downloaded) "Open" else "Open")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Dismiss")
        }
        if (!result.received) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Dismiss to return and try again.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
