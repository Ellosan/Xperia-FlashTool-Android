package dev.flashtool.xperia

import dev.flashtool.xperia.ftf.Ftf
import dev.flashtool.xperia.ftf.ImageCategory
import org.junit.Assert.assertEquals
import org.junit.Test

class FlashPlanTest {

    @Test
    fun `maps sony image names onto flashing stages`() {
        val expected = mapOf(
            "loader.sin" to ImageCategory.LOADER,
            "partition.sin" to ImageCategory.PARTITION,
            "gpt_main0.sin" to ImageCategory.PARTITION,
            "aboot_X-FLASH-ALL.sin" to ImageCategory.BOOTLOADER,
            "tz_S1-BOOT.sin" to ImageCategory.BOOTLOADER,
            "amss_fsg.sin" to ImageCategory.MODEM,
            "kernel_X-FLASH-C.sin" to ImageCategory.KERNEL,
            "boot.sin" to ImageCategory.KERNEL,
            "system_X-FLASH-ALL.sin" to ImageCategory.SYSTEM,
            "vendor.sin" to ImageCategory.SYSTEM,
            "fota.sin" to ImageCategory.FOTA,
            "userdata.sin" to ImageCategory.USERDATA,
            "cache.sin" to ImageCategory.USERDATA,
            "something-else.sin" to ImageCategory.OTHER,
        )

        expected.forEach { (name, category) ->
            assertEquals("wrong stage for $name", category, Ftf.categorise(name))
        }
    }

    @Test
    fun `flashing order puts the partition table first and user data last`() {
        val order = ImageCategory.entries.sortedBy { it.order }.map { it.name }

        assertEquals("LOADER", order.first())
        assertEquals("PARTITION", order[1])
        assertEquals("OTHER", order.last())
        assertEquals(ImageCategory.entries.size - 2, order.indexOf("USERDATA"))
    }
}
