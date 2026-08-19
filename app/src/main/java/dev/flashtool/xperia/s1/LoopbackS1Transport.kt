package dev.flashtool.xperia.s1

import dev.flashtool.xperia.core.beInt
import dev.flashtool.xperia.core.toBeBytes
import java.io.ByteArrayOutputStream

/**
 * A stand-in loader that answers S1 frames in memory.
 *
 * Two uses: the app's dry-run mode, which walks the whole flashing pipeline (archive reading, SIN
 * parsing, ordering, progress) without a phone attached, and the unit tests, which would otherwise
 * need hardware. It deliberately answers everything successfully — it verifies the framing, not
 * the firmware.
 */
class LoopbackS1Transport(
    private val maxPacketSize: Int = 64 * 1024,
    private val loaderVersion: String = "LOOPBACK-LOADER 1.0",
) : S1Transport {

    override val description: String = "Simulated loader (dry run)"

    /** Every command the fake loader was asked to perform, in order. Assertions read this. */
    val received = mutableListOf<S1Packet>()

    /** Total payload bytes accepted through SEND_DATA. */
    var bytesWritten: Long = 0L
        private set

    private val inbound = ByteArrayOutputStream()
    private val outbound = ByteArrayOutputStream()
    private var outboundOffset = 0
    private var closed = false

    override fun write(data: ByteArray, offset: Int, length: Int, timeoutMs: Int) {
        check(!closed) { "Transport is closed" }
        inbound.write(data, offset, length)
        drainFrames()
    }

    override fun read(dest: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
        val pending = outbound.toByteArray()
        val available = pending.size - outboundOffset
        if (available <= 0) return 0
        val n = minOf(available, length)
        System.arraycopy(pending, outboundOffset, dest, offset, n)
        outboundOffset += n
        if (outboundOffset == pending.size) {
            outbound.reset()
            outboundOffset = 0
        }
        return n
    }

    override fun close() {
        closed = true
    }

    private fun drainFrames() {
        var buffer = inbound.toByteArray()
        var consumed = 0
        while (buffer.size - consumed >= S1Packet.HEADER_SIZE) {
            val payloadLength = buffer.beInt(consumed + 8)
            val frameSize = S1Packet.frameSize(payloadLength)
            if (buffer.size - consumed < frameSize) break

            val frame = buffer.copyOfRange(consumed, consumed + frameSize)
            consumed += frameSize
            handle(S1Packet.decode(frame))
        }
        if (consumed > 0) {
            val rest = buffer.copyOfRange(consumed, buffer.size)
            inbound.reset()
            inbound.write(rest)
            buffer = rest
        }
    }

    private fun handle(packet: S1Packet) {
        received += packet
        if (packet.command == S1Command.SEND_DATA.code) bytesWritten += packet.payload.size

        // A real loader stays silent for continuation frames; only the last one is acknowledged.
        if (packet.isMoreData) return

        val reply = when (S1Command.fromCode(packet.command)) {
            S1Command.GET_INFO -> when (packet.payload.firstOrNull()?.toInt()) {
                S1Info.LOADER_VERSION -> loaderVersion.toByteArray(Charsets.US_ASCII)
                S1Info.MAX_PACKET_SIZE -> maxPacketSize.toBeBytes()
                else -> ByteArray(0)
            }

            S1Command.READ_TA -> byteArrayOf(0x00, 0x00, 0x00, 0x00)
            S1Command.REBOOT -> return // the phone is gone before it can answer
            else -> ByteArray(0)
        }
        outbound.write(S1Packet(packet.sequence, packet.command, S1Packet.FLAG_NONE, reply).encode())
    }
}
