package dev.flashtool.xperia

import dev.flashtool.xperia.core.RandomAccessFileSource
import dev.flashtool.xperia.flash.FlashEngine
import dev.flashtool.xperia.flash.FlashPhase
import dev.flashtool.xperia.flash.FlashPlan
import dev.flashtool.xperia.ftf.Ftf
import dev.flashtool.xperia.ftf.ImageCategory
import dev.flashtool.xperia.s1.LoopbackS1Transport
import dev.flashtool.xperia.s1.S1Command
import dev.flashtool.xperia.s1.S1IoException
import dev.flashtool.xperia.s1.S1Transport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FlashEngineTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val loaderData = 32 * 1024
    private val systemData = 300 * 1024
    private val userdataData = 20 * 1024

    private fun buildFtf(): File = FtfTestData.zip(
        temp.newFile("firmware.ftf"),
        linkedMapOf(
            // Deliberately out of flashing order in the archive.
            "system.sin" to FtfTestData.sin(512, 3, systemData, 0x11),
            "userdata.sin" to FtfTestData.sin(512, 3, userdataData, 0x22),
            "loader.sin" to FtfTestData.sin(256, 3, loaderData, 0x33),
            "partition.sin" to FtfTestData.sin(256, 3, 4096, 0x44),
            "boot.ta" to "// units\n02\n000008A2 00000004 00 00 00 01\n".toByteArray(),
        ),
    )

    private fun openFtf(): Ftf = Ftf.open("firmware.ftf", RandomAccessFileSource(buildFtf()))

    @Test
    fun `classifies images and separates the loader`() {
        openFtf().use { ftf ->
            assertNotNull(ftf.loader)
            assertEquals("loader.sin", ftf.loader!!.displayName)
            assertEquals(loaderData.toLong(), ftf.loader!!.dataLength)

            assertEquals(listOf("partition.sin", "system.sin", "userdata.sin"), ftf.images.map { it.displayName })
            assertEquals(ImageCategory.PARTITION, ftf.images[0].category)
            assertEquals(ImageCategory.USERDATA, ftf.images[2].category)
            assertEquals(1, ftf.taFiles.size)
            assertEquals(2, ftf.taFiles[0].content.partition)
        }
    }

    @Test
    fun `default selection leaves user data alone`() {
        openFtf().use { ftf ->
            val selection = FlashPlan.defaultSelection(ftf)
            assertTrue(selection.none { it.contains("userdata") })
            assertTrue(selection.any { it.contains("system") })
        }
    }

    @Test
    fun `flashes every selected byte in partition order`() = runBlocking {
        openFtf().use { ftf ->
            val plan = FlashPlan.build(
                ftf = ftf,
                selectedImages = FlashPlan.defaultSelection(ftf),
                selectedTa = ftf.taFiles.map { it.entryName }.toSet(),
                rebootWhenDone = true,
            )
            val transport = LoopbackS1Transport()
            val engine = FlashEngine()

            engine.run(plan, transport, dryRun = true)

            val state = engine.state.value
            assertEquals(FlashPhase.DONE, state.phase)
            assertEquals(1f, state.overallFraction, 0.0001f)

            // Every payload byte of the loader plus the selected images reached the loader.
            assertEquals(plan.totalBytes, transport.bytesWritten)
            assertEquals((loaderData + 4096 + systemData).toLong(), transport.bytesWritten)

            // Headers arrive in flashing order: loader, partition table, then system.
            val headerSizes = transport.received
                .filter { it.command == S1Command.SEND_HEADER.code }
                .map { it.payload.size }
            assertEquals(listOf(256, 256, 512), headerSizes)

            val commands = transport.received.map { it.command }
            assertTrue(commands.contains(S1Command.OPEN_TA.code))
            assertTrue(commands.contains(S1Command.WRITE_TA.code))
            assertTrue(commands.contains(S1Command.CLOSE_TA.code))
            assertEquals(1, commands.count { it == S1Command.END_SESSION.code })
            assertEquals(1, commands.count { it == S1Command.REBOOT.code })

            // TA writing happens after the loader is up but before any image.
            val firstImageHeader = transport.received.indexOfLast { it.command == S1Command.SEND_HEADER.code }
            val taWrite = transport.received.indexOfFirst { it.command == S1Command.WRITE_TA.code }
            assertTrue(taWrite < firstImageHeader)
        }
    }

    @Test
    fun `splits payloads into packets the loader agreed to`() = runBlocking {
        openFtf().use { ftf ->
            val chunk = 16 * 1024
            val plan = FlashPlan.build(ftf, setOf("system.sin"), emptySet(), rebootWhenDone = false)
            val transport = LoopbackS1Transport(maxPacketSize = chunk)

            FlashEngine().run(plan, transport, dryRun = true)

            // The loader itself goes out at the default chunk size — the negotiated maximum is
            // only known once the loader is running — so measure the image that follows it.
            val systemHeader = transport.received.indexOfLast { it.command == S1Command.SEND_HEADER.code }
            val imagePackets = transport.received
                .drop(systemHeader + 1)
                .filter { it.command == S1Command.SEND_DATA.code }

            assertTrue(imagePackets.all { it.payload.size <= chunk })
            assertEquals((systemData + chunk - 1) / chunk, imagePackets.size)
            assertEquals(systemData.toLong(), imagePackets.sumOf { it.payload.size.toLong() })
            // Only the final packet of a file clears the more-data flag.
            assertEquals(1, imagePackets.count { !it.isMoreData })
        }
    }

    @Test
    fun `records the failure when the link dies mid-flash`() = runBlocking {
        openFtf().use { ftf ->
            val plan = FlashPlan.build(ftf, setOf("system.sin"), emptySet(), rebootWhenDone = false)
            val engine = FlashEngine()

            val failure = runCatching { engine.run(plan, BrokenTransport(), dryRun = false) }

            assertTrue(failure.isFailure)
            assertEquals(FlashPhase.FAILED, engine.state.value.phase)
            assertNotNull(engine.state.value.error)
        }
    }

    private class BrokenTransport : S1Transport {
        override val description = "cable pulled"
        override fun write(data: ByteArray, offset: Int, length: Int, timeoutMs: Int) =
            throw S1IoException("USB write failed")

        override fun read(dest: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int =
            throw S1IoException("USB read failed")

        override fun close() = Unit
    }
}
