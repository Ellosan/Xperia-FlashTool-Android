package dev.flashtool.xperia

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Builds throwaway FTF-shaped archives so the tests can exercise the real reader. */
object FtfTestData {

    fun sin(headerLength: Int, version: Int, dataSize: Int, fill: Byte): ByteArray {
        val bytes = ByteArray(headerLength + dataSize)
        bytes[0] = (headerLength ushr 24).toByte()
        bytes[1] = (headerLength ushr 16).toByte()
        bytes[2] = (headerLength ushr 8).toByte()
        bytes[3] = headerLength.toByte()
        bytes[4] = version.toByte()
        java.util.Arrays.fill(bytes, headerLength, bytes.size, fill)
        return bytes
    }

    /** Writes a zip; entries whose name is in [stored] go in uncompressed, as real FTFs do. */
    fun zip(file: File, entries: Map<String, ByteArray>, stored: Set<String> = emptySet()): File {
        ZipOutputStream(file.outputStream().buffered()).use { out ->
            for ((name, bytes) in entries) {
                val entry = ZipEntry(name)
                if (name in stored) {
                    entry.method = ZipEntry.STORED
                    entry.size = bytes.size.toLong()
                    entry.compressedSize = bytes.size.toLong()
                    entry.crc = java.util.zip.CRC32().apply { update(bytes) }.value
                }
                out.putNextEntry(entry)
                out.write(bytes)
                out.closeEntry()
            }
        }
        return file
    }
}
