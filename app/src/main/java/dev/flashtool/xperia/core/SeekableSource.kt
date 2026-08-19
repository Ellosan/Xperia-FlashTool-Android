package dev.flashtool.xperia.core

import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile

/**
 * Random-access byte source. FTF files routinely run to several gigabytes, so the archive reader
 * seeks around the original file instead of copying it into app storage first.
 */
interface SeekableSource : Closeable {
    val size: Long

    /** Reads up to [length] bytes starting at absolute [position]; returns -1 at end of file. */
    @Throws(IOException::class)
    fun readAt(position: Long, dest: ByteArray, offset: Int, length: Int): Int

    @Throws(IOException::class)
    fun readFullyAt(position: Long, dest: ByteArray, offset: Int = 0, length: Int = dest.size - offset) {
        var done = 0
        while (done < length) {
            val n = readAt(position + done, dest, offset + done, length - done)
            if (n <= 0) throw EOFException("Wanted $length bytes at $position, got $done")
            done += n
        }
    }

    fun readFullyAt(position: Long, length: Int): ByteArray =
        ByteArray(length).also { readFullyAt(position, it) }

    /** A one-shot stream over [length] bytes starting at [position]. */
    fun slice(position: Long, length: Long): InputStream = SliceInputStream(this, position, length)
}

class RandomAccessFileSource(private val file: RandomAccessFile) : SeekableSource {
    constructor(file: File) : this(RandomAccessFile(file, "r"))

    override val size: Long get() = file.length()

    @Synchronized
    override fun readAt(position: Long, dest: ByteArray, offset: Int, length: Int): Int {
        if (position >= size) return -1
        file.seek(position)
        return file.read(dest, offset, length)
    }

    override fun close() = file.close()
}

private class SliceInputStream(
    private val source: SeekableSource,
    start: Long,
    private val length: Long,
) : InputStream() {

    private val start = start
    private var pos = 0L

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) == 1) one[0].toInt() and 0xFF else -1
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (pos >= length) return -1
        val want = minOf(len.toLong(), length - pos).toInt()
        val n = source.readAt(start + pos, b, off, want)
        if (n > 0) pos += n
        return n
    }

    override fun available(): Int = (length - pos).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    override fun skip(n: Long): Long {
        val skipped = minOf(n, length - pos)
        pos += skipped
        return skipped
    }
}
