package com.easyshare.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.easyshare.app.ui.state.ConnectStep
import com.easyshare.app.ui.state.QueueItemStatus
import com.easyshare.app.ui.state.SessionUiState
import com.easyshare.app.ui.state.TransferProgressUi
import com.easyshare.app.ui.state.TransferQueueItemUi
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun SessionStatusHeader(
    state: SessionUiState,
    strategyLabel: String? = null,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = state.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = state.subtitle,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
        )
        if (!strategyLabel.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(12.dp))
            AssistChip(
                onClick = {},
                enabled = false,
                label = { Text(strategyLabel) }
            )
        }
    }
}

@Composable
fun ConnectStepper(
    current: ConnectStep,
    modifier: Modifier = Modifier
) {
    val steps = ConnectStep.entries
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        steps.forEach { step ->
            val selected = step.ordinal <= current.ordinal
            FilterChip(
                selected = selected,
                onClick = {},
                enabled = false,
                label = {
                    Text(
                        text = when (step) {
                            ConnectStep.QrExchanged -> "QR"
                            ConnectStep.Gathering -> "Gather"
                            ConnectStep.Checking -> "Check"
                            ConnectStep.Connected -> "Link"
                        }
                    )
                }
            )
        }
    }
}

@Composable
fun TransferProgressPanel(
    progress: TransferProgressUi,
    modifier: Modifier = Modifier
) {
    val overall by animateFloatAsState(progress.overallFraction, label = "overall")
    val current by animateFloatAsState(progress.currentFraction, label = "current")

    Column(modifier = modifier.fillMaxWidth()) {
        Text("Overall", style = MaterialTheme.typography.labelLarge)
        Spacer(modifier = Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { overall },
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = formatProgressLine(
                progress.bytesDone,
                progress.bytesTotal,
                progress.speedBytesPerSec,
                progress.etaSeconds
            ),
            style = MaterialTheme.typography.bodyMedium
        )

        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = progress.currentFileName ?: "Current file",
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { current },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = formatBytesPair(progress.currentFileDone, progress.currentFileTotal),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )

        if (progress.queue.isNotEmpty()) {
            Spacer(modifier = Modifier.height(20.dp))
            Text("Queue", style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(8.dp))
            progress.queue.forEach { item ->
                QueueRow(item)
            }
        }
    }
}

@Composable
private fun QueueRow(item: TransferQueueItemUi) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = when (item.status) {
                QueueItemStatus.Waiting -> Icons.Default.HourglassEmpty
                QueueItemStatus.Active -> Icons.Default.Sync
                QueueItemStatus.Done -> Icons.Default.CheckCircle
                QueueItemStatus.Failed -> Icons.Default.Error
            },
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = when (item.status) {
                QueueItemStatus.Failed -> MaterialTheme.colorScheme.error
                QueueItemStatus.Done -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            }
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = item.name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
    val gb = mb / 1024.0
    return String.format(Locale.US, "%.2f GB", gb)
}

fun formatBytesPair(done: Long, total: Long): String =
    "${formatBytes(done)} / ${formatBytes(total)}"

fun formatProgressLine(done: Long, total: Long, speedBps: Long, etaSeconds: Long?): String {
    val pct = if (total <= 0L) 0 else ((done * 100.0) / total).roundToInt().coerceIn(0, 100)
    val speed = if (speedBps <= 0L) "—" else "${formatBytes(speedBps)}/s"
    val eta = etaSeconds?.let { secs ->
        if (secs < 60) "${secs}s left"
        else "${secs / 60}m ${secs % 60}s left"
    } ?: "…"
    return "$pct% · ${formatBytesPair(done, total)} · $speed · $eta"
}
