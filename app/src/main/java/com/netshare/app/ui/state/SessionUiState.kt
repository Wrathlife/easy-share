package com.netshare.app.ui.state

/**
 * Platform-neutral session phases. Wire protocol must not embed Android URIs.
 * Pairing defaults to a generated share code; QR is an optional alternate.
 */
sealed interface SessionUiState {
    val title: String
    val subtitle: String

    data object Idle : SessionUiState {
        override val title = "Netshare"
        override val subtitle =
            "Share files over the internet with a short code. Pairing is online; file bytes stay peer-to-peer (not relayed)."
    }

    data object PreparingShare : SessionUiState {
        override val title = "Preparing share"
        override val subtitle = "Building file list and connection details…"
    }

    data object ChooseFilesToShare : SessionUiState {
        override val title = "Choose files to share"
        override val subtitle = "Only you pick what to send. The other device just receives."
    }

    data class ShowOfferCode(
        val code: String,
        val strategyLabel: String = "Internet pairing"
    ) : SessionUiState {
        override val title = "Your share code"
        override val subtitle = "Send this code to the other device. They enter it to pair."
    }

    data object EnterOfferCode : SessionUiState {
        override val title = "Enter share code"
        override val subtitle = "Join the sharer. You won’t pick files — only download theirs."
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
        override val subtitle = "Shared files will appear here for download."
    }

    /** Guest paired; waiting for host manifest (no local file picking). */
    data object WaitingForSharedFiles : SessionUiState {
        override val title = "Paired"
        override val subtitle = "Waiting for the sharer’s files. You don’t choose what to send."
    }

    data object HostPaired : SessionUiState {
        override val title = "Paired"
        override val subtitle = "The other device joined. Ready to send your selected files."
    }

    data class ConfirmDevices(val phrase: String) : SessionUiState {
        override val title = "Confirm devices"
        override val subtitle =
            "Both screens should show “$phrase”. Confirm only if they match."
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
    CodeExchanged,
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
