package dev.flashtool.xperia

import dev.flashtool.xperia.core.RandomAccessFileSource
import dev.flashtool.xperia.ftf.ZipArchive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class ZipArchiveTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `reads stored and deflated entries`() {
        val deflatable = ByteArray(200_000) { (it % 7).toByte() }
        val incompressible = ByteArray(50_000).also { java.util.Random(1).nextBytes(it) }
        val file = FtfTestData.zip(
            temp.newFile("test.ftf"),
            mapOf("compressed.sin" to deflatable, "raw.sin" to incompressible),
            stored = setOf("raw.sin"),
        )

        ZipArchive(RandomAccessFileSource(file)).use { archive ->
            assertEquals(2, archive.entries.size)

            val compressed = archive.entry("compressed.sin")
            assertNotNull(compressed)
            assertEquals(deflatable.size.toLong(), compressed!!.size)
            assertArrayEquals(deflatable, archive.open(compressed).readBytes())

            val raw = archive.entry("raw.sin")!!
            assertArrayEquals(incompressible, archive.open(raw).readBytes())
        }
    }

    @Test
    fun `peek returns only the requested prefix`() {
        val payload = ByteArray(100_000) { it.toByte() }
        val file = FtfTestData.zip(temp.newFile("peek.ftf"), mapOf("a.sin" to payload))

        ZipArchive(RandomAccessFileSource(file)).use { archive ->
            val prefix = archive.peek(archive.entry("a.sin")!!, 1024)
            assertArrayEquals(payload.copyOf(1024), prefix)
        }
    }

    @Test
    fun `peek is capped at the entry size`() {
        val file = FtfTestData.zip(temp.newFile("short.ftf"), mapOf("tiny.sin" to ByteArray(10)))

        ZipArchive(RandomAccessFileSource(file)).use { archive ->
            assertEquals(10, archive.peek(archive.entry("tiny.sin")!!, 4096).size)
        }
    }

    @Test(expected = IOException::class)
    fun `rejects a file that is not a zip`() {
        val file = temp.newFile("notazip.ftf").apply { writeBytes(ByteArray(5000) { 0x41 }) }
        ZipArchive(RandomAccessFileSource(file))
    }
}
