package com.subcast.subtitle

import android.content.Context
import android.net.Uri

/** Reads a subtitle content:// Uri into bytes via the ContentResolver. */
object SubtitleFileReader {

    fun readBytes(context: Context, uri: Uri): ByteArray {
        context.contentResolver.openInputStream(uri).use { input ->
            return input?.readBytes() ?: error("Cannot read subtitle: $uri")
        }
    }
}
