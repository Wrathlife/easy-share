package com.easyshare.app.files

import android.net.Uri

/**
 * Local-only SAF tree mapping. Only relative paths cross the wire.
 */
data class LocalShareEntry(
    val relativePath: String,
    val uri: Uri,
    val sizeBytes: Long
)

interface SafTreeReader {
    suspend fun collect(uris: List<Uri>): List<LocalShareEntry>
}

class PlaceholderSafTreeReader : SafTreeReader {
    override suspend fun collect(uris: List<Uri>): List<LocalShareEntry> = emptyList()
}
