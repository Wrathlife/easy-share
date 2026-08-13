package com.netshare.app.files

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

object ReceivedFileOpener {
    fun open(context: Context, displayName: String, localUri: String?) {
        if (localUri.isNullOrBlank()) {
            Toast.makeText(
                context,
                "“$displayName” isn’t downloaded yet — only the share list arrived",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val uri = runCatching { Uri.parse(localUri) }.getOrNull()
        if (uri == null) {
            Toast.makeText(context, "Can’t open this file", Toast.LENGTH_SHORT).show()
            return
        }
        val viewUri = when (uri.scheme) {
            "file" -> {
                val path = uri.path
                if (path.isNullOrBlank()) {
                    Toast.makeText(context, "Can’t open this file", Toast.LENGTH_SHORT).show()
                    return
                }
                val file = File(path)
                if (!file.exists()) {
                    Toast.makeText(context, "File is no longer on this device", Toast.LENGTH_LONG).show()
                    return
                }
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
            }
            else -> uri
        }
        val mime = guessMime(displayName)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(viewUri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(Intent.createChooser(intent, "Open $displayName"))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "No app found to open this file type", Toast.LENGTH_LONG).show()
        } catch (_: SecurityException) {
            Toast.makeText(context, "Permission denied opening file", Toast.LENGTH_LONG).show()
        }
    }

    private fun guessMime(name: String): String {
        val ext = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        if (ext.isBlank()) return "application/octet-stream"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "application/octet-stream"
    }
}
