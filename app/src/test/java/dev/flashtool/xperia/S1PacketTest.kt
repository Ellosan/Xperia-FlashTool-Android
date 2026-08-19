package dev.flashtool.xperia

import dev.flashtool.xperia.core.beInt
import dev.flashtool.xperia.s1.S1Command
import dev.flashtool.xperia.s1.S1Packet
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class S1PacketTest {

    @Test
    fun `round trips through the wire format`() {
        val packet = S1Packet(7, S1Command.SEND_DATA.code, S1Packet.FLAG_MORE_DATA, byteArrayOf(1, 2, 3, 4, 5))
        val decoded = S1Packet.decode(packet.encode())

        assertEquals(packet, decoded)
        assertTrue(decoded.isMoreData)
    }

    @Test
    fun `header fields land at the documented offsets`() {
        val frame = S1Packet(0x11223344, 0x05, 0x01, ByteArray(3)).encode()

        assertEquals(0x11223344, frame.beInt(0))
        assertEquals(0x05, frame.beInt(4))
        assertEquals(3, frame.beInt(8))
        assertEquals(0x01, frame.beInt(12))
        assertEquals(S1Packet.frameSize(3), frame.size)
    }

    @Test
    fun `crc covers the header and the payload`() {
        val frame = S1Packet(1, 0x06, 0, byteArrayOf(9, 9, 9)).encode()
        assertTrue(S1Packet.verifyCrc(frame))

        frame[S1Packet.HEADER_SIZE] = 0x08
        assertFalse(S1Packet.verifyCrc(frame))
    }

    @Test
    fun `packets sent without a crc still validate`() {
        val frame = S1Packet(1, 0x06, 0, byteArrayOf(4, 5, 6)).encode(withCrc = false)

        assertEquals(S1Packet.CRC_SKIP, frame.beInt(frame.size - 4))
        assertTrue(S1Packet.verifyCrc(frame))
    }

    @Test
    fun `empty payloads are legal`() {
        val decoded = S1Packet.decode(S1Packet(1, S1Command.CLOSE_TA.code, 0, ByteArray(0)).encode())
        assertArrayEquals(ByteArray(0), decoded.payload)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `truncated frames are rejected`() {
        S1Packet.decode(ByteArray(8))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `frames claiming more payload than they carry are rejected`() {
        val frame = S1Packet(1, 0x05, 0, byteArrayOf(1, 2)).encode()
        frame[11] = 0x7F // inflate the declared length
        S1Packet.decode(frame)
    }
}
