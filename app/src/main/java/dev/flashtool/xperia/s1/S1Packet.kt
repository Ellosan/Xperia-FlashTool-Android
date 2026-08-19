package dev.flashtool.xperia.s1

import dev.flashtool.xperia.core.asPrintable
import dev.flashtool.xperia.core.beInt
import dev.flashtool.xperia.core.crc32
import dev.flashtool.xperia.core.toBeBytes

/**
 * One S1 frame.
 *
 * Wire layout (all fields big-endian):
 * ```
 *   0  ..  3   sequence number, starts at 1 and increments per command
 *   4  ..  7   command id (see [S1Command])
 *   8  .. 11   payload length
 *  12  .. 15   flags
 *  16  ..      payload
 *  last 4      CRC32 over everything before it
 * ```
 * Some loaders accept 0xFFFFFFFF in place of a real CRC; [encode] can emit that form for
 * bulk data chunks, where recomputing a CRC per 64 KiB packet is pure overhead.
 */
data class S1Packet(
    val sequence: Int,
    val command: Int,
    val flags: Int,
    val payload: ByteArray,
) {
    val isMoreData: Boolean get() = (flags and FLAG_MORE_DATA) != 0

    fun encode(withCrc: Boolean = true): ByteArray {
        val header = ByteArray(HEADER_SIZE)
        sequence.toBeBytes().copyInto(header, 0)
        command.toBeBytes().copyInto(header, 4)
        payload.size.toBeBytes().copyInto(header, 8)
        flags.toBeBytes().copyInto(header, 12)

        val crc = if (withCrc) crc32(header, payload) else CRC_SKIP
        return ByteArray(HEADER_SIZE + payload.size + CRC_SIZE).also { out ->
            header.copyInto(out, 0)
            payload.copyInto(out, HEADER_SIZE)
            crc.toBeBytes().copyInto(out, HEADER_SIZE + payload.size)
        }
    }

    override fun toString(): String =
        "S1Packet(seq=$sequence, cmd=${S1Command.describe(command)}, flags=0x%08X, len=${payload.size}, payload=${payload.asPrintable(64)})"
            .format(flags)

    override fun equals(other: Any?): Boolean =
        other is S1Packet && sequence == other.sequence && command == other.command &&
            flags == other.flags && payload.contentEquals(other.payload)

    override fun hashCode(): Int =
        (sequence * 31 + command) * 31 + flags * 31 + payload.contentHashCode()

    companion object {
        const val HEADER_SIZE = 16
        const val CRC_SIZE = 4
        const val FLAG_NONE = 0x00000000
        const val FLAG_MORE_DATA = 0x00000001
        const val CRC_SKIP = -1 // 0xFFFFFFFF

        /** Total frame size for a payload of [payloadSize] bytes. */
        fun frameSize(payloadSize: Int): Int = HEADER_SIZE + payloadSize + CRC_SIZE

        /**
         * Reads the payload length out of a (possibly partial) frame, or -1 if the header is
         * not complete yet. Used by the reader to know how many more bytes to pull off the wire.
         */
        fun peekPayloadLength(buffer: ByteArray, available: Int): Int =
            if (available < HEADER_SIZE) -1 else buffer.beInt(8)

        fun decode(frame: ByteArray): S1Packet {
            require(frame.size >= HEADER_SIZE + CRC_SIZE) {
                "S1 frame too short: ${frame.size} bytes"
            }
            val length = frame.beInt(8)
            require(length >= 0 && HEADER_SIZE + length + CRC_SIZE <= frame.size) {
                "S1 frame claims $length payload bytes but only ${frame.size - HEADER_SIZE - CRC_SIZE} are present"
            }
            return S1Packet(
                sequence = frame.beInt(0),
                command = frame.beInt(4),
                flags = frame.beInt(12),
                payload = frame.copyOfRange(HEADER_SIZE, HEADER_SIZE + length),
            )
        }

        /** True when the trailing CRC matches, or when the sender opted out of CRCs. */
        fun verifyCrc(frame: ByteArray): Boolean {
            val length = frame.beInt(8)
            val crcOffset = HEADER_SIZE + length
            if (crcOffset + CRC_SIZE > frame.size) return false
            val actual = frame.beInt(crcOffset)
            if (actual == CRC_SKIP) return true
            return actual == crc32(frame.copyOfRange(0, crcOffset))
        }
    }
}
