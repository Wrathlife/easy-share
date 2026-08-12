package com.easyshare.app.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import com.easyshare.app.debug.AgentDebugLog
import com.easyshare.app.signaling.InternetCodeSignaling

data class LocalShareEntry(
    val relativePath: String,
    val uri: Uri,
    val sizeBytes: Long,
    val displayName: String
)

class SafShareCollector(private val context: Context) {

    fun takePersistableRead(uri: Uri, isTree: Boolean) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, flags)
        }
    }

    fun collectFiles(uris: List<Uri>): List<LocalShareEntry> {
        val used = linkedSetOf<String>()
        var collisions = 0
        val result = uris.mapNotNull { uri ->
            val name = queryDisplayName(uri) ?: return@mapNotNull null
            val safeName = sanitizeSegment(name) ?: return@mapNotNull null
            val size = querySize(uri)
            val path = uniquePath(safeName, used)
            if (path != safeName) collisions++
            LocalShareEntry(
                relativePath = path,
                uri = uri,
                sizeBytes = size,
                displayName = name
            )
        }
        // #region agent log
        AgentDebugLog.log(
            hypothesisId = "H2",
            location = "SafShareCollector.collectFiles",
            message = "collected files with unique paths",
            data = mapOf("count" to result.size, "collisions" to collisions),
            runId = "fix-review2"
        )
        // #endregion
        return result
    }

    fun collectTree(treeUri: Uri): List<LocalShareEntry> {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
        val out = mutableListOf<LocalShareEntry>()
        val prefix = sanitizeSegment(root.name?.takeIf { it.isNotBlank() } ?: "shared") ?: "shared"
        walk(root, prefix = prefix, out = out, depth = 0)
        return out
    }

    /**
     * Merge new entries into an existing selection, uniqueness by URI and relativePath.
     */
    fun mergeUnique(
        existing: List<LocalShareEntry>,
        incoming: List<LocalShareEntry>
    ): List<LocalShareEntry> {
        val byUri = LinkedHashMap<String, LocalShareEntry>()
        val usedPaths = linkedSetOf<String>()
        fun add(entry: LocalShareEntry) {
            val uriKey = entry.uri.toString()
            if (byUri.containsKey(uriKey)) return
            var path = entry.relativePath
            if (!usedPaths.add(path)) {
                path = uniquePath(path, usedPaths)
            }
            byUri[uriKey] = entry.copy(relativePath = path)
        }
        existing.forEach(::add)
        incoming.forEach(::add)
        return byUri.values.toList().take(InternetCodeSignaling.MAX_MANIFEST_FILES)
    }

    private fun walk(
        dir: DocumentFile,
        prefix: String,
        out: MutableList<LocalShareEntry>,
        depth: Int
    ) {
        if (out.size >= InternetCodeSignaling.MAX_MANIFEST_FILES) return
        if (depth > MAX_TREE_DEPTH) return
        for (child in dir.listFiles()) {
            if (out.size >= InternetCodeSignaling.MAX_MANIFEST_FILES) return
            val name = sanitizeSegment(child.name ?: continue) ?: continue
            val path = "$prefix/$name"
            if (child.isDirectory) {
                walk(child, path, out, depth + 1)
            } else if (child.isFile) {
                out += LocalShareEntry(
                    relativePath = path,
                    uri = child.uri,
                    sizeBytes = child.length().coerceAtLeast(0L),
                    displayName = name
                )
            }
        }
    }

    private fun uniquePath(displayName: String, used: MutableSet<String>): String {
        if (used.add(displayName)) return displayName
        val dot = displayName.lastIndexOf('.')
        val stem = if (dot > 0) displayName.substring(0, dot) else displayName
        val ext = if (dot > 0) displayName.substring(dot) else ""
        var i = 2
        while (true) {
            val candidate = "$stem ($i)$ext"
            if (used.add(candidate)) return candidate
            i++
        }
    }

    private fun sanitizeSegment(raw: String): String? {
        val cleaned = raw.trim().replace('\\', '/').substringAfterLast('/')
        if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") return null
        if (cleaned.contains('/')) return null
        return cleaned.take(120)
    }

    private fun queryDisplayName(uri: Uri): String? {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) return c.getString(idx)
                }
            }
        return DocumentsContract.getDocumentId(uri)?.substringAfterLast(':')
            ?: uri.lastPathSegment
    }

    private fun querySize(uri: Uri): Long {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.SIZE)
                    if (idx >= 0 && !c.isNull(idx)) return c.getLong(idx).coerceAtLeast(0L)
                }
            }
        return -1L
    }

    companion object {
        private const val MAX_TREE_DEPTH = 32
    }
}
