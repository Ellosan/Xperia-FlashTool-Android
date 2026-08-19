package dev.flashtool.xperia.ftf

import dev.flashtool.xperia.core.beInt

/**
 * The parts of a SIN (Sony INstaller) file the flasher needs.
 *
 * A SIN is a header followed by the raw partition image. The header carries the hashes and the
 * target-partition description; the loader on the phone is what validates and interprets it, so
 * the app only has to find where the header ends and pass it through untouched.
 *
 * Layout: bytes 0..3 are the header length (big-endian), byte 4 is the format version.
 */
data class SinHeader(
    val headerLength: Int,
    val version: Int,
    val raw: ByteArray,
    /** Partition name lifted out of the header when one is embedded, for display only. */
    val partitionHint: String?,
) {
    override fun equals(other: Any?): Boolean =
        other is SinHeader && headerLength == other.headerLength && version == other.version &&
            raw.contentEquals(other.raw) && partitionHint == other.partitionHint

    override fun hashCode(): Int = (headerLength * 31 + version) * 31 + raw.contentHashCode()
}

object SinFile {

    /** Header lengths outside this range mean the file is not a SIN (or is a raw image). */
    private const val MIN_HEADER = 12
    private const val MAX_HEADER = 1 shl 20

    /** Four-character block tags Sony uses inside SIN v3 headers. */
    private val BLOCK_TAGS = setOf("MMCF", "GPTP", "ADDF", "PRTN", "HASH")

    /**
     * Parses the header from the first bytes of a SIN file. [prefix] must hold at least the first
     * four bytes; when it is shorter than the announced header, [raw] is truncated and the caller
     * should re-read with a bigger prefix.
     *
     * Returns null when the file has no recognisable SIN header, in which case it is flashed as a
     * raw image with no header packet.
     */
    fun parseHeader(prefix: ByteArray, fileLength: Long): SinHeader? {
        if (prefix.size < 5) return null
        val headerLength = prefix.beInt(0)
        if (headerLength < MIN_HEADER || headerLength > MAX_HEADER) return null
        if (headerLength > fileLength) return null

        val version = prefix[4].toInt() and 0xFF
        val raw = prefix.copyOfRange(0, minOf(headerLength, prefix.size))
        return SinHeader(headerLength, version, raw, findPartitionHint(raw))
    }

    /** How many bytes of the file to read before parsing; covers all headers we have seen. */
    const val PREFIX_SIZE = 8 * 1024

    /**
     * Best-effort extraction of a human-readable partition name. Sony embeds the target as an
     * ASCII string inside the header; this pulls out the most plausible candidate rather than
     * pretending to fully decode a format that is not documented.
     */
    private fun findPartitionHint(header: ByteArray): String? {
        val candidates = ArrayList<String>()
        val current = StringBuilder()
        for (b in header) {
            val c = (b.toInt() and 0xFF).toChar()
            if (c.code in 0x21..0x7E && (c.isLetterOrDigit() || c in "_-./")) {
                current.append(c)
            } else {
                if (current.length >= 3) candidates += current.toString()
                current.setLength(0)
            }
        }
        if (current.length >= 3) candidates += current.toString()

        return candidates
            .filterNot { it in BLOCK_TAGS }
            .filter { it.length in 3..40 }
            .maxByOrNull { if (it.contains('/')) it.length + 100 else it.length }
    }
}
