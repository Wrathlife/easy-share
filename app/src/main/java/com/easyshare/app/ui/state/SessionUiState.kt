package com.easyshare.app.ui.state

/**
 * Platform-neutral session phases. Wire protocol / QR must not embed Android URIs.
 */
sealed interface SessionUiState {
    val title: String
    val subtitle: String

    data object Idle : SessionUiState {
        override val title = "Easy Share"
        override val subtitle = "Share files peer-to-peer — no account, no cloud relay."
    }

    data object PreparingShare : SessionUiState {
        override val title = "Preparing share"
        override val subtitle = "Building file list and connection details…"
    }

    data class ShowOfferQr(
        val strategyLabel: String = "WAN P2P"
    ) : SessionUiState {
        override val title = "Show this QR"
        override val subtitle = "Step 1 of 2 — other device scans this code."
    }

    data object ScanOfferQr : SessionUiState {
        override val title = "Scan their QR"
        override val subtitle = "Step 1 of 2 — point at the sharer’s code."
    }

    data object ShowAnswerQr : SessionUiState {
        override val title = "Show your reply QR"
        override val subtitle = "Step 2 of 2 — sharer scans this to finish pairing."
    }

    data object ScanAnswerQr : SessionUiState {
        override val title = "Scan their reply"
        override val subtitle = "Step 2 of 2 — scan the guest’s answer QR."
    }

    data class Connecting(
        val strategyLabel: String,
        val detail: String
    ) : SessionUiState {
        override val title = "Connecting"
        override val subtitle = detail
    }

    data class Retrying(
        val strategyLabel: String,
        val reason: String
    ) : SessionUiState {
        override val title = "Trying another path"
        override val subtitle = reason
    }

    data object ConnectedBrowsing : SessionUiState {
        override val title = "Connected"
        override val subtitle = "Pick what to download from the shared tree."
    }

    data class Transferring(
        val progress: TransferProgressUi
    ) : SessionUiState {
        override val title = if (progress.sending) "Sending files" else "Receiving files"
        override val subtitle = progress.currentFileName?.let { "Current: $it" } ?: "Transfer in progress…"
    }

    data object Verifying : SessionUiState {
        override val title = "Verifying"
        override val subtitle = "Checking file integrity…"
    }

    data object Completed : SessionUiState {
        override val title = "Done"
        override val subtitle = "Transfer finished successfully."
    }

    data object Cancelled : SessionUiState {
        override val title = "Cancelled"
        override val subtitle = "The share was stopped."
    }

    data class Failed(
        val diagnosis: String,
        val actions: List<String>
    ) : SessionUiState {
        override val title = "Couldn’t connect"
        override val subtitle = diagnosis
    }
}

enum class ConnectStep {
    QrExchanged,
    Gathering,
    Checking,
    Connected
}

data class TransferProgressUi(
    val sending: Boolean,
    val bytesDone: Long,
    val bytesTotal: Long,
    val currentFileName: String?,
    val currentFileDone: Long,
    val currentFileTotal: Long,
    val speedBytesPerSec: Long,
    val etaSeconds: Long?,
    val queue: List<TransferQueueItemUi> = emptyList()
) {
    val overallFraction: Float
        get() = if (bytesTotal <= 0L) 0f else (bytesDone.toFloat() / bytesTotal.toFloat()).coerceIn(0f, 1f)

    val currentFraction: Float
        get() = if (currentFileTotal <= 0L) 0f else {
            (currentFileDone.toFloat() / currentFileTotal.toFloat()).coerceIn(0f, 1f)
        }
}

enum class QueueItemStatus { Waiting, Active, Done, Failed }

data class TransferQueueItemUi(
    val name: String,
    val status: QueueItemStatus
)
