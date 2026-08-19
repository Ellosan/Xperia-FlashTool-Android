package dev.flashtool.xperia.s1

import dev.flashtool.xperia.core.FlashLog
import dev.flashtool.xperia.core.asPrintable
import dev.flashtool.xperia.core.beInt
import dev.flashtool.xperia.core.humanSize
import dev.flashtool.xperia.core.toBeBytes
import java.io.Closeable
import java.io.InputStream

/**
 * A conversation with the loader: framing, sequence numbers, reply checking and the handful of
 * higher-level operations (send an image, read/write TA units, reboot).
 *
 * Not thread-safe — one flash job owns one session.
 */
class S1Session(
    private val transport: S1Transport,
    /** Payload bytes per data packet. Renegotiated from the loader once it is running. */
    var chunkSize: Int = DEFAULT_CHUNK_SIZE,
) : Closeable {

    private var sequence = 1
    private val readBuffer = ByteArray(MAX_FRAME)
    private var buffered = 0

    /** True once a loader (rather than the boot ROM) is answering. */
    var loaderRunning: Boolean = false
        private set

    var loaderVersion: String? = null
        private set

    // ---------------------------------------------------------------- framing

    private fun send(command: S1Command, payload: ByteArray, flags: Int, withCrc: Boolean) {
        val packet = S1Packet(sequence, command.code, flags, payload)
        FlashLog.d("-> $packet")
        transport.write(packet.encode(withCrc))
    }

    /** Pulls exactly one frame off the wire, blocking until it is complete. */
    private fun receive(timeoutMs: Int): S1Packet {
        // Header first, so we know how much payload to expect.
        fillTo(S1Packet.HEADER_SIZE, timeoutMs)
        val payloadLength = readBuffer.beInt(8)
        if (payloadLength < 0 || payloadLength > MAX_PAYLOAD) {
            throw S1IoException("Loader announced an implausible payload of $payloadLength bytes — link out of sync")
        }
        val frameSize = S1Packet.frameSize(payloadLength)
        fillTo(frameSize, timeoutMs)

        val frame = readBuffer.copyOfRange(0, frameSize)
        // Consume the frame, keeping anything that belonged to the next one.
        System.arraycopy(readBuffer, frameSize, readBuffer, 0, buffered - frameSize)
        buffered -= frameSize

        if (!S1Packet.verifyCrc(frame)) {
            throw S1IoException("CRC mismatch on a reply from the phone — bad cable or a stalled endpoint")
        }
        return S1Packet.decode(frame).also { FlashLog.d("<- $it") }
    }

    private fun fillTo(target: Int, timeoutMs: Int) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (buffered < target) {
            val remaining = (deadline - System.currentTimeMillis()).toInt()
            if (remaining <= 0) {
                throw S1IoException("Timed out waiting for the phone (wanted $target bytes, got $buffered)")
            }
            val n = transport.read(readBuffer, buffered, readBuffer.size - buffered, remaining)
            if (n < 0) throw S1IoException("USB link closed while reading a reply")
            buffered += n
        }
    }

    /**
     * Sends one command and returns the loader's reply, concatenating continuation frames.
     * Throws [S1ProtocolException] when the loader answers with anything but the same command id,
     * which is how it signals a rejected request.
     */
    fun command(
        command: S1Command,
        payload: ByteArray = ByteArray(0),
        flags: Int = S1Packet.FLAG_NONE,
        withCrc: Boolean = true,
        timeoutMs: Int = S1Transport.DEFAULT_TIMEOUT_MS,
        expectReply: Boolean = true,
    ): ByteArray {
        send(command, payload, flags, withCrc)
        sequence++
        if (!expectReply) return ByteArray(0)

        val collected = ArrayList<ByteArray>()
        while (true) {
            val reply = receive(timeoutMs)
            if (reply.command != command.code) {
                throw S1ProtocolException(command, reply.command, reply.payload.asPrintable())
            }
            collected += reply.payload
            if (!reply.isMoreData) break
        }
        return if (collected.size == 1) collected[0] else collected.reduce { a, b -> a + b }
    }

    // ------------------------------------------------------------- operations

    /**
     * Sends loader.sin to the boot ROM and picks up the loader's identification. Everything else
     * in a flash session happens after this.
     */
    fun sendLoader(header: ByteArray, data: InputStream, dataLength: Long, onProgress: (Long) -> Unit = {}) {
        FlashLog.i("Sending loader (${humanSize(dataLength)})")
        sendFile("loader.sin", header, data, dataLength, onProgress)
        loaderRunning = true
        loaderVersion = runCatching { getInfo(S1Info.LOADER_VERSION).asPrintable() }
            .onFailure { FlashLog.w("Loader is up but did not report a version: ${it.message}") }
            .getOrNull()
        loaderVersion?.let { FlashLog.ok("Loader running: $it") }

        runCatching { getInfo(S1Info.MAX_PACKET_SIZE) }.getOrNull()?.let { reply ->
            if (reply.size >= 4) {
                val max = reply.beInt(0)
                if (max in 4096..MAX_PAYLOAD) {
                    chunkSize = max
                    FlashLog.i("Loader accepts ${humanSize(max.toLong())} per packet")
                }
            }
        }
    }

    /**
     * Streams one image to the phone: the SIN header first (the loader verifies its hashes and
     * decides where the image goes), then the payload in [chunkSize] pieces.
     */
    fun sendFile(
        name: String,
        header: ByteArray,
        data: InputStream,
        dataLength: Long,
        onProgress: (Long) -> Unit = {},
    ) {
        if (header.isNotEmpty()) {
            command(S1Command.SEND_HEADER, header, timeoutMs = HEADER_TIMEOUT_MS)
        }

        val buffer = ByteArray(chunkSize)
        var sent = 0L
        while (sent < dataLength) {
            val want = minOf(chunkSize.toLong(), dataLength - sent).toInt()
            val got = data.readFully(buffer, want)
            if (got <= 0) throw S1IoException("$name ended after $sent of $dataLength bytes")

            val last = sent + got >= dataLength
            val flags = if (last) S1Packet.FLAG_NONE else S1Packet.FLAG_MORE_DATA
            val chunk = if (got == buffer.size) buffer else buffer.copyOf(got)

            // Only the final packet of a file is acknowledged; asking for an ack per chunk would
            // halve the throughput on a link that is already the bottleneck.
            command(
                command = S1Command.SEND_DATA,
                payload = chunk,
                flags = flags,
                withCrc = false,
                timeoutMs = DATA_TIMEOUT_MS,
                expectReply = last,
            )
            sent += got
            onProgress(sent)
        }
    }

    fun getInfo(selector: Int): ByteArray =
        command(S1Command.GET_INFO, byteArrayOf(selector.toByte()))

    fun openTa(partition: Int) {
        command(S1Command.OPEN_TA, partition.toBeBytes())
        FlashLog.i("TA partition $partition open")
    }

    fun closeTa() {
        command(S1Command.CLOSE_TA)
    }

    fun readTaUnit(unit: Int): ByteArray = command(S1Command.READ_TA, unit.toBeBytes())

    fun writeTaUnit(unit: Int, value: ByteArray) {
        val payload = ByteArray(8 + value.size)
        unit.toBeBytes().copyInto(payload, 0)
        value.size.toBeBytes().copyInto(payload, 4)
        value.copyInto(payload, 8)
        command(S1Command.WRITE_TA, payload)
    }

    fun endSession() {
        runCatching { command(S1Command.END_SESSION, timeoutMs = SHORT_TIMEOUT_MS) }
            .onFailure { FlashLog.w("End-of-session was not acknowledged: ${it.message}") }
    }

    /** Asks the phone to leave flash mode. The reply usually never arrives — it reboots first. */
    fun reboot() {
        runCatching {
            command(S1Command.REBOOT, timeoutMs = SHORT_TIMEOUT_MS, expectReply = false)
        }.onFailure { FlashLog.w("Reboot request failed: ${it.message}") }
    }

    override fun close() {
        transport.close()
    }

    private companion object {
        const val DEFAULT_CHUNK_SIZE = 64 * 1024
        const val MAX_PAYLOAD = 1 shl 20
        const val MAX_FRAME = MAX_PAYLOAD + S1Packet.HEADER_SIZE + S1Packet.CRC_SIZE
        const val HEADER_TIMEOUT_MS = 60_000
        const val DATA_TIMEOUT_MS = 120_000
        const val SHORT_TIMEOUT_MS = 3_000
    }
}

/** Reads [count] bytes unless the stream ends first. */
private fun InputStream.readFully(into: ByteArray, count: Int): Int {
    var read = 0
    while (read < count) {
        val n = read(into, read, count - read)
        if (n < 0) break
        read += n
    }
    return read
}
