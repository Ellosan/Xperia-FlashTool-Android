package dev.flashtool.xperia.ftf

import java.io.IOException

/**
 * A Trim Area unit: a small key/value record in the phone's TA partitions holding things like the
 * SIM lock state, the bootloader-unlock allowance and the customisation id.
 */
data class TaUnit(val unit: Int, val value: ByteArray) {
    val label: String get() = "%08X".format(unit)

    override fun equals(other: Any?): Boolean =
        other is TaUnit && unit == other.unit && value.contentEquals(other.value)

    override fun hashCode(): Int = unit * 31 + value.contentHashCode()
}

data class TaContent(val partition: Int, val units: List<TaUnit>)

/**
 * Parser for Flashtool's textual `.ta` format:
 * ```
 *   // a comment
 *   01                      <- partition number
 *   000008A2 00000004 00 00 00 01   <- unit id, length, then that many bytes
 * ```
 * Whitespace and line breaks are not significant inside a record, so a unit's bytes may wrap over
 * as many lines as the file likes.
 */
object TaFile {

    fun parse(text: String): TaContent {
        val tokens = tokenize(text)
        if (tokens.isEmpty()) throw IOException("TA file is empty")

        val iterator = tokens.iterator()
        val partition = iterator.next().toIntOrNull(16)
            ?: throw IOException("TA file does not start with a partition number")

        val units = ArrayList<TaUnit>()
        while (iterator.hasNext()) {
            val unit = iterator.next().toLongOrNull(16)?.toInt()
                ?: throw IOException("Malformed unit id in TA file")
            if (!iterator.hasNext()) throw IOException("TA unit %08X has no length".format(unit))
            val length = iterator.next().toLongOrNull(16)?.toInt()
                ?: throw IOException("Malformed length for TA unit %08X".format(unit))
            if (length < 0 || length > MAX_UNIT_SIZE) {
                throw IOException("TA unit %08X declares an implausible length of $length bytes".format(unit))
            }

            val value = ByteArray(length)
            for (i in 0 until length) {
                if (!iterator.hasNext()) {
                    throw IOException("TA unit %08X is truncated at byte $i of $length".format(unit))
                }
                value[i] = (
                    iterator.next().toIntOrNull(16)
                        ?: throw IOException("Non-hex byte in TA unit %08X".format(unit))
                    ).toByte()
            }
            units += TaUnit(unit, value)
        }
        return TaContent(partition, units)
    }

    /** Renders units back out in the same format, for TA backups. */
    fun write(content: TaContent): String = buildString {
        append("// Trim Area backup written by Xperia Flashtool for Android\n")
        append("%02X\n".format(content.partition))
        for (unit in content.units) {
            append("%08X %08X".format(unit.unit, unit.value.size))
            for (b in unit.value) append(" %02X".format(b))
            append('\n')
        }
    }

    private fun tokenize(text: String): List<String> = text
        .lineSequence()
        .map { it.substringBefore("//").trim() }
        .filter { it.isNotEmpty() }
        .flatMap { it.split(WHITESPACE).asSequence() }
        .filter { it.isNotEmpty() }
        .toList()

    private val WHITESPACE = Regex("\\s+")
    private const val MAX_UNIT_SIZE = 1 shl 20
}
