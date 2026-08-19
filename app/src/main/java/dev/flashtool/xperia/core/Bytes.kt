package dev.flashtool.xperia.core

import java.util.Locale
import java.util.zip.CRC32

/** Big-endian helpers. The S1 protocol is big-endian throughout. */
fun Int.toBeBytes(): ByteArray = byteArrayOf(
    (this ushr 24).toByte(),
    (this ushr 16).toByte(),
    (this ushr 8).toByte(),
    this.toByte(),
)

fun ByteArray.beInt(offset: Int = 0): Int =
    ((this[offset].toInt() and 0xFF) shl 24) or
        ((this[offset + 1].toInt() and 0xFF) shl 16) or
        ((this[offset + 2].toInt() and 0xFF) shl 8) or
        (this[offset + 3].toInt() and 0xFF)

fun ByteArray.beShort(offset: Int = 0): Int =
    ((this[offset].toInt() and 0xFF) shl 8) or (this[offset + 1].toInt() and 0xFF)

fun crc32(vararg parts: ByteArray): Int {
    val crc = CRC32()
    parts.forEach { crc.update(it) }
    return crc.value.toInt()
}

fun ByteArray.toHex(separator: String = " ", limit: Int = Int.MAX_VALUE): String {
    val shown = if (size > limit) limit else size
    val sb = StringBuilder(shown * 3)
    for (i in 0 until shown) {
        if (i > 0) sb.append(separator)
        sb.append(String.format(Locale.US, "%02X", this[i]))
    }
    if (size > shown) sb.append("… (+${size - shown} bytes)")
    return sb.toString()
}

/** Renders a payload as text when it is printable ASCII, otherwise as hex. Loader replies are often strings. */
fun ByteArray.asPrintable(limit: Int = 128): String {
    if (isEmpty()) return "<empty>"
    val printable = all { it == 0x0A.toByte() || it == 0x0D.toByte() || it == 0x09.toByte() || (it >= 0x20 && it < 0x7F) }
    return if (printable) String(this, Charsets.US_ASCII).trim() else toHex(limit = limit)
}

fun humanSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.US, "%.2f GiB", bytes.toDouble() / (1L shl 30))
    bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MiB", bytes.toDouble() / (1L shl 20))
    bytes >= 1L shl 10 -> String.format(Locale.US, "%.1f KiB", bytes.toDouble() / (1L shl 10))
    else -> "$bytes B"
}
