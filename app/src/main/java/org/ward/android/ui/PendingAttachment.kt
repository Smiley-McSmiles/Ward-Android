package org.ward.android.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.ward.android.xmpp.guessMime

/**
 * A file picked in the composer that has not been sent yet. The composer keeps these in a tray
 * above the input pill, so a picked photo waits for the send button instead of going out at once.
 */
data class PendingAttachment(
    val uri: Uri,
    val name: String,
    val mime: String,
    val size: Long = 0
) {
    val isImage: Boolean get() = mime.startsWith("image/")
    val isVideo: Boolean get() = mime.startsWith("video/")
}

fun pendingAttachment(context: Context, uri: Uri): PendingAttachment? {
    var name = ""
    var size = 0L
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val nameIndex = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && !c.isNull(nameIndex)) name = c.getString(nameIndex).orEmpty()
                val sizeIndex = c.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !c.isNull(sizeIndex)) size = c.getLong(sizeIndex)
            }
        }
    }
    val fallbackName = uri.lastPathSegment?.substringAfterLast('/').orEmpty()
    val displayName = name.ifBlank { fallbackName }.ifBlank { "attachment" }
    val declared = runCatching { context.contentResolver.getType(uri) }.getOrNull().orEmpty()
    val mime = declared.ifBlank { guessMime(displayName) }
    return PendingAttachment(uri, displayName, mime, size)
}
