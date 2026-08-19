package dev.flashtool.xperia.ftf

import dev.flashtool.xperia.core.SeekableSource
import java.io.IOException
import java.io.InputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * Minimal read-only ZIP reader with ZIP64 support, built directly on a [SeekableSource].
 *
 * java.util.zip.ZipFile needs a real path, and ZipInputStream would force a linear scan of a
 * multi-gigabyte FTF every time an image is read. This reads the central directory once and then
 * streams any entry on demand.
 */
class ZipArchive(private val source: SeekableSource) : AutoCloseable {

    data class Entry(
        val name: String,
        val compressedSize: Long,
        val size: Long,
        val method: Int,
        val localHeaderOffset: Long,
    ) {
        val isDirectory: Boolean get() = name.endsWith("/")
        val fileName: String get() = name.substringAfterLast('/')
    }

    val entries: List<Entry> = readCentralDirectory()

    fun entry(name: String): Entry? = entries.firstOrNull { it.name.equals(name, ignoreCase = true) }

    /** Opens the entry's uncompressed content. */
    fun open(entry: Entry): InputStream {
        val dataOffset = dataOffsetOf(entry)
        val raw = source.slice(dataOffset, entry.compressedSize)
        return when (entry.method) {
            METHOD_STORED -> raw
            METHOD_DEFLATED -> InflaterInputStream(raw, Inflater(true), 64 * 1024)
            else -> throw IOException("Unsupported compression method ${entry.method} for ${entry.name}")
        }
    }

    /** Reads the first [count] uncompressed bytes of an entry. */
    fun peek(entry: Entry, count: Int): ByteArray = open(entry).use { stream ->
        val buffer = ByteArray(count)
        var read = 0
        while (read < count) {
            val n = stream.read(buffer, read, count - read)
            if (n < 0) break
            read += n
        }
        if (read == count) buffer else buffer.copyOf(read)
    }

    /** Absolute file offset of an entry's payload; needs the local header, whose extra field can differ. */
    private fun dataOffsetOf(entry: Entry): Long {
        val local = source.readFullyAt(entry.localHeaderOffset, LOCAL_HEADER_SIZE)
        if (le32(local, 0) != LOCAL_HEADER_SIG) {
            throw IOException("Corrupt archive: no local header for ${entry.name}")
        }
        val nameLength = le16(local, 26)
        val extraLength = le16(local, 28)
        return entry.localHeaderOffset + LOCAL_HEADER_SIZE + nameLength + extraLength
    }

    private fun readCentralDirectory(): List<Entry> {
        val eocdOffset = findEndOfCentralDirectory()
        val eocd = source.readFullyAt(eocdOffset, EOCD_SIZE)

        var entryCount = le16(eocd, 10).toLong()
        var directoryOffset = le32(eocd, 16).toLong() and 0xFFFFFFFFL
        var directorySize = le32(eocd, 12).toLong() and 0xFFFFFFFFL

        // ZIP64 takes over once any of the classic 32-bit fields is saturated.
        if (entryCount == 0xFFFFL || directoryOffset == 0xFFFFFFFFL || directorySize == 0xFFFFFFFFL) {
            val locatorOffset = eocdOffset - ZIP64_LOCATOR_SIZE
            if (locatorOffset >= 0) {
                val locator = source.readFullyAt(locatorOffset, ZIP64_LOCATOR_SIZE)
                if (le32(locator, 0) == ZIP64_LOCATOR_SIG) {
                    val zip64Offset = le64(locator, 8)
                    val zip64 = source.readFullyAt(zip64Offset, ZIP64_EOCD_SIZE)
                    if (le32(zip64, 0) != ZIP64_EOCD_SIG) throw IOException("Corrupt ZIP64 end record")
                    entryCount = le64(zip64, 32)
                    directorySize = le64(zip64, 40)
                    directoryOffset = le64(zip64, 48)
                }
            }
        }

        if (directorySize <= 0 || directorySize > MAX_DIRECTORY_SIZE) {
            throw IOException("Implausible central directory size: $directorySize bytes")
        }

        val directory = source.readFullyAt(directoryOffset, directorySize.toInt())
        val result = ArrayList<Entry>(entryCount.coerceAtMost(4096).toInt())
        var p = 0
        while (p + CENTRAL_HEADER_SIZE <= directory.size && le32(directory, p) == CENTRAL_HEADER_SIG) {
            val method = le16(directory, p + 10)
            var compressed = le32(directory, p + 20).toLong() and 0xFFFFFFFFL
            var uncompressed = le32(directory, p + 24).toLong() and 0xFFFFFFFFL
            val nameLength = le16(directory, p + 28)
            val extraLength = le16(directory, p + 30)
            val commentLength = le16(directory, p + 32)
            var localOffset = le32(directory, p + 42).toLong() and 0xFFFFFFFFL

            val name = String(directory, p + CENTRAL_HEADER_SIZE, nameLength, Charsets.UTF_8)
            val extraStart = p + CENTRAL_HEADER_SIZE + nameLength

            if (uncompressed == 0xFFFFFFFFL || compressed == 0xFFFFFFFFL || localOffset == 0xFFFFFFFFL) {
                var e = extraStart
                val extraEnd = extraStart + extraLength
                while (e + 4 <= extraEnd) {
                    val id = le16(directory, e)
                    val len = le16(directory, e + 2)
                    if (id == ZIP64_EXTRA_ID) {
                        var f = e + 4
                        if (uncompressed == 0xFFFFFFFFL && f + 8 <= extraEnd) { uncompressed = le64(directory, f); f += 8 }
                        if (compressed == 0xFFFFFFFFL && f + 8 <= extraEnd) { compressed = le64(directory, f); f += 8 }
                        if (localOffset == 0xFFFFFFFFL && f + 8 <= extraEnd) { localOffset = le64(directory, f) }
                        break
                    }
                    e += 4 + len
                }
            }

            result += Entry(name, compressed, uncompressed, method, localOffset)
            p = extraStart + extraLength + commentLength
        }
        return result
    }

    private fun findEndOfCentralDirectory(): Long {
        val searchLength = minOf(source.size, (MAX_COMMENT_SIZE + EOCD_SIZE).toLong())
        if (searchLength < EOCD_SIZE) throw IOException("File is too small to be a ZIP archive")
        val start = source.size - searchLength
        val tail = source.readFullyAt(start, searchLength.toInt())
        for (i in tail.size - EOCD_SIZE downTo 0) {
            if (le32(tail, i) == EOCD_SIG) return start + i
        }
        throw IOException("Not a ZIP archive — no end-of-central-directory record found")
    }

    override fun close() = source.close()

    private companion object {
        const val EOCD_SIG = 0x06054B50
        const val EOCD_SIZE = 22
        const val MAX_COMMENT_SIZE = 0xFFFF
        const val CENTRAL_HEADER_SIG = 0x02014B50
        const val CENTRAL_HEADER_SIZE = 46
        const val LOCAL_HEADER_SIG = 0x04034B50
        const val LOCAL_HEADER_SIZE = 30
        const val ZIP64_LOCATOR_SIG = 0x07064B50
        const val ZIP64_LOCATOR_SIZE = 20
        const val ZIP64_EOCD_SIG = 0x06064B50
        const val ZIP64_EOCD_SIZE = 56
        const val ZIP64_EXTRA_ID = 0x0001
        const val METHOD_STORED = 0
        const val METHOD_DEFLATED = 8
        const val MAX_DIRECTORY_SIZE = 64L * 1024 * 1024

        fun le16(b: ByteArray, o: Int): Int = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

        fun le32(b: ByteArray, o: Int): Int =
            (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
                ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

        fun le64(b: ByteArray, o: Int): Long =
            (le32(b, o).toLong() and 0xFFFFFFFFL) or ((le32(b, o + 4).toLong() and 0xFFFFFFFFL) shl 32)
    }
}
