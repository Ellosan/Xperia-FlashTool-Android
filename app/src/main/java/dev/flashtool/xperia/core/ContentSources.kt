package dev.flashtool.xperia.core

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer

/** [SeekableSource] over a SAF document, so a 2 GB FTF is read in place instead of copied. */
class ContentUriSource(context: Context, uri: Uri) : SeekableSource {

    private val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
        ?: throw IOException("Cannot open $uri")

    private val stream = FileInputStream(descriptor.fileDescriptor)
    private val channel = stream.channel

    override val size: Long = descriptor.statSize.takeIf { it >= 0 } ?: channel.size()

    override fun readAt(position: Long, dest: ByteArray, offset: Int, length: Int): Int =
        channel.read(ByteBuffer.wrap(dest, offset, length), position)

    override fun close() {
        runCatching { channel.close() }
        runCatching { stream.close() }
        runCatching { descriptor.close() }
    }
}

fun Context.displayNameOf(uri: Uri): String {
    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getString(0)
    }
    return uri.lastPathSegment?.substringAfterLast('/') ?: "firmware.ftf"
}
