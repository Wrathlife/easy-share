package com.netshare.app.transfer

import java.io.File
import java.util.Locale

/**
 * Shared transfer safety rails — not product UX caps.
 * Streaming is chunked; these only stop absurd / disk-full cases.
 */
object TransferLimits {
    /** Ridiculous per-file ceiling (~100 GiB). Normal multi‑GB shares are fine. */
    const val MAX_FILE_BYTES: Long = 100L * 1024L * 1024L * 1024L

    /** Extra headroom when checking receiver free space. */
    const val FREE_SPACE_MARGIN_BYTES: Long = 64L * 1024L * 1024L

    fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
        val gb = mb / 1024.0
        if (gb < 1024) return String.format(Locale.US, "%.2f GB", gb)
        val tb = gb / 1024.0
        return String.format(Locale.US, "%.2f TB", tb)
    }

    fun tooLargeMessage(displayName: String): String =
        "“$displayName” is too large (max ${formatSize(MAX_FILE_BYTES)})"

    /**
     * @return error message if [neededBytes] (+ margin) exceeds usable space under [dir], else null.
     * Skips the check when [neededBytes] is unknown/non-positive.
     */
    fun insufficientSpaceMessage(dir: File, neededBytes: Long): String? {
        if (neededBytes <= 0L) return null
        dir.mkdirs()
        val free = runCatching { dir.usableSpace }.getOrDefault(0L)
        val required = neededBytes + FREE_SPACE_MARGIN_BYTES
        if (free >= required) return null
        return "Not enough free space to receive ${formatSize(neededBytes)} " +
            "(${formatSize(free)} free, need about ${formatSize(required)})"
    }
}
