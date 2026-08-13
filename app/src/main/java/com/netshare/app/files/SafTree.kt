package com.netshare.app.files

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import com.netshare.app.debug.AgentDebugLog
import com.netshare.app.signaling.InternetCodeSignaling

data class LocalShareEntry(
    val relativePath: String,
    val uri: Uri,
    val sizeBytes: Long,
    val displayName: String
)

class SafShareCollector(context: Context) {
    private val context = context.applicationContext

    fun takePersistableRead(uri: Uri, takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION) {
        val flags = takeFlags and (
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        val tryFlags = if (flags != 0) flags else Intent.FLAG_GRANT_READ_URI_PERMISSION
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, tryFlags)
        }.recoverCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        // Never throw — temporary grant from the picker is enough for this session.
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

    /**
     * Recursively collect files under a SAF tree URI.
     * Never throws — returns empty on access failures (OEMs vary wildly here).
     */
    fun collectTree(treeUri: Uri): List<LocalShareEntry> {
        val out = runCatching { collectTreeViaDocumentsContract(treeUri) }
            .onFailure { err ->
                AgentDebugLog.log(
                    hypothesisId = "H-FOLDER",
                    location = "SafShareCollector.collectTree.contract",
                    message = "DocumentsContract walk failed",
                    data = mapOf("error" to (err.message ?: err.toString())),
                    runId = "folder-crash-fix"
                )
            }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?: runCatching { collectTreeViaDocumentFile(treeUri) }
                .onFailure { err ->
                    AgentDebugLog.log(
                        hypothesisId = "H-FOLDER",
                        location = "SafShareCollector.collectTree.docfile",
                        message = "DocumentFile walk failed",
                        data = mapOf("error" to (err.message ?: err.toString())),
                        runId = "folder-crash-fix"
                    )
                }
                .getOrDefault(emptyList())

        AgentDebugLog.log(
            hypothesisId = "H-FOLDER",
            location = "SafShareCollector.collectTree",
            message = "collected tree",
            data = mapOf(
                "count" to out.size,
                "uri" to treeUri.toString().take(120)
            ),
            runId = "folder-crash-fix"
        )
        return out
    }

    private fun collectTreeViaDocumentsContract(treeUri: Uri): List<LocalShareEntry> {
        val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootDocId =
            if (DocumentsContract.isDocumentUri(context, treeUri)) {
                runCatching { DocumentsContract.getDocumentId(treeUri) }.getOrDefault(treeDocId)
            } else {
                treeDocId
            }
        val rootName = queryTreeName(treeUri, rootDocId)
            ?: runCatching { DocumentFile.fromTreeUri(context, treeUri)?.name }.getOrNull()
            ?: "shared"
        val prefix = sanitizeSegment(rootName) ?: "shared"
        val out = mutableListOf<LocalShareEntry>()
        walkDocuments(
            treeUri = treeUri,
            parentDocId = rootDocId,
            prefix = prefix,
            out = out,
            depth = 0
        )
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

    private fun collectTreeViaDocumentFile(treeUri: Uri): List<LocalShareEntry> {
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: DocumentFile.fromSingleUri(context, treeUri)
            ?: return emptyList()
        val out = mutableListOf<LocalShareEntry>()
        val prefix = sanitizeSegment(root.name?.takeIf { it.isNotBlank() } ?: "shared") ?: "shared"
        walkDocumentFile(root, prefix = prefix, out = out, depth = 0)
        return out
    }

    private fun walkDocuments(
        treeUri: Uri,
        parentDocId: String,
        prefix: String,
        out: MutableList<LocalShareEntry>,
        depth: Int
    ) {
        if (out.size >= InternetCodeSignaling.MAX_MANIFEST_FILES) return
        if (depth > MAX_TREE_DEPTH) return

        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val cursor: Cursor = try {
            context.contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE
                ),
                null,
                null,
                null
            )
        } catch (_: Exception) {
            null
        } ?: return

        cursor.use { c ->
            val idIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            if (idIdx < 0) return
            val nameIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)

            while (c.moveToNext()) {
                if (out.size >= InternetCodeSignaling.MAX_MANIFEST_FILES) return
                val docId = runCatching { c.getString(idIdx) }.getOrNull() ?: continue
                val rawName = when {
                    nameIdx >= 0 -> runCatching { c.getString(nameIdx) }.getOrNull()
                    else -> null
                } ?: docId.substringAfterLast(':')
                val name = sanitizeSegment(rawName) ?: continue
                val mime = if (mimeIdx >= 0) runCatching { c.getString(mimeIdx) }.getOrNull() else null
                val path = "$prefix/$name"
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    walkDocuments(treeUri, docId, path, out, depth + 1)
                } else {
                    // Null / unknown MIME: treat as file (DocumentFile.isFile is false when type is null).
                    val size = if (sizeIdx >= 0) {
                        runCatching {
                            if (!c.isNull(sizeIdx)) c.getLong(sizeIdx).coerceAtLeast(0L) else 0L
                        }.getOrDefault(0L)
                    } else {
                        0L
                    }
                    out += LocalShareEntry(
                        relativePath = path,
                        uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId),
                        sizeBytes = size,
                        displayName = name
                    )
                }
            }
        }
    }

    private fun walkDocumentFile(
        dir: DocumentFile,
        prefix: String,
        out: MutableList<LocalShareEntry>,
        depth: Int
    ) {
        if (out.size >= InternetCodeSignaling.MAX_MANIFEST_FILES) return
        if (depth > MAX_TREE_DEPTH) return
        val children = runCatching { dir.listFiles() }.getOrDefault(emptyArray())
        for (child in children) {
            if (out.size >= InternetCodeSignaling.MAX_MANIFEST_FILES) return
            val name = sanitizeSegment(child.name ?: continue) ?: continue
            val path = "$prefix/$name"
            val isDir = runCatching { child.isDirectory }.getOrDefault(false)
            if (isDir) {
                walkDocumentFile(child, path, out, depth + 1)
            } else {
                // Prefer !directory over isFile — null MIME makes isFile false on some OEMs.
                out += LocalShareEntry(
                    relativePath = path,
                    uri = child.uri,
                    sizeBytes = runCatching { child.length().coerceAtLeast(0L) }.getOrDefault(0L),
                    displayName = name
                )
            }
        }
    }

    private fun queryTreeName(treeUri: Uri, docId: String): String? {
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        context.contentResolver.query(
            docUri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null
        )?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                if (idx >= 0) return c.getString(idx)
            }
        }
        return null
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
