package dev.flashtool.xperia

import dev.flashtool.xperia.ftf.TaFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class TaFileTest {

    private val sample = """
        // Trim Area units for a test device
        02
        000008A2 00000004 00 00 00 01
        00000929 00000002
            FF FF
    """.trimIndent()

    @Test
    fun `parses partition and units across line breaks`() {
        val content = TaFile.parse(sample)

        assertEquals(2, content.partition)
        assertEquals(2, content.units.size)
        assertEquals(0x8A2, content.units[0].unit)
        assertArrayEquals(byteArrayOf(0, 0, 0, 1), content.units[0].value)
        assertArrayEquals(byteArrayOf(-1, -1), content.units[1].value)
    }

    @Test
    fun `survives a write and read round trip`() {
        val original = TaFile.parse(sample)
        val reparsed = TaFile.parse(TaFile.write(original))

        assertEquals(original.partition, reparsed.partition)
        assertEquals(original.units, reparsed.units)
    }

    @Test(expected = IOException::class)
    fun `rejects a truncated unit`() {
        TaFile.parse("01\n000008A2 00000004 00 00")
    }

    @Test(expected = IOException::class)
    fun `rejects an empty file`() {
        TaFile.parse("// nothing but a comment\n")
    }
}
