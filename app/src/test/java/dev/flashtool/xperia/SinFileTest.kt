package dev.flashtool.xperia

import dev.flashtool.xperia.ftf.SinFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SinFileTest {

    private fun sinHeader(length: Int, version: Int, embedded: String? = null): ByteArray {
        val header = ByteArray(length)
        header[0] = (length ushr 24).toByte()
        header[1] = (length ushr 16).toByte()
        header[2] = (length ushr 8).toByte()
        header[3] = length.toByte()
        header[4] = version.toByte()
        embedded?.toByteArray(Charsets.US_ASCII)?.copyInto(header, 16)
        return header
    }

    @Test
    fun `reads the header length and version`() {
        val header = SinFile.parseHeader(sinHeader(512, 3), fileLength = 4096)

        assertNotNull(header)
        assertEquals(512, header!!.headerLength)
        assertEquals(3, header.version)
    }

    @Test
    fun `finds the embedded partition path`() {
        val header = SinFile.parseHeader(sinHeader(256, 3, "/dev/block/bootdevice/by-name/system"), 8192)

        assertEquals("/dev/block/bootdevice/by-name/system", header?.partitionHint)
    }

    @Test
    fun `rejects a header longer than the file`() {
        assertNull(SinFile.parseHeader(sinHeader(4096, 3), fileLength = 1024))
    }

    @Test
    fun `treats random data as a raw image`() {
        // A JPEG-ish prefix: the first four bytes are not a plausible header length.
        assertNull(SinFile.parseHeader(byteArrayOf(-1, -40, -1, -32, 0, 16), fileLength = 100_000))
    }

    @Test
    fun `needs at least five bytes`() {
        assertNull(SinFile.parseHeader(byteArrayOf(0, 0, 1, 0), fileLength = 100_000))
    }
}
