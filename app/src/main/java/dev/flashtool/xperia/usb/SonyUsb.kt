package dev.flashtool.xperia.usb

import android.hardware.usb.UsbDevice

/** USB identity of Sony's various boot modes. */
object SonyUsb {

    /** Sony / Sony Ericsson Mobile Communications. */
    const val VENDOR_ID = 0x0FCE

    /** S1 flash mode — volume down held while connecting, green LED. This is what we flash over. */
    val FLASHMODE_PRODUCT_IDS = setOf(0xADDE)

    /** fastboot — volume up held while connecting, blue LED. Not usable for FTF flashing. */
    val FASTBOOT_PRODUCT_IDS = setOf(0x0DDE, 0x0DDA)

    fun modeOf(device: UsbDevice): SonyMode = when {
        device.vendorId != VENDOR_ID -> SonyMode.NOT_SONY
        device.productId in FLASHMODE_PRODUCT_IDS -> SonyMode.FLASHMODE
        device.productId in FASTBOOT_PRODUCT_IDS -> SonyMode.FASTBOOT
        else -> SonyMode.OTHER
    }
}

enum class SonyMode(val title: String, val hint: String) {
    FLASHMODE(
        "Flash mode (S1)",
        "Ready to flash.",
    ),
    FASTBOOT(
        "Fastboot",
        "Fastboot cannot flash FTF files. Power the phone off, then hold VOLUME DOWN while connecting it.",
    ),
    OTHER(
        "Sony device (normal mode)",
        "The phone is booted normally. Power it off, then hold VOLUME DOWN while connecting it to enter flash mode.",
    ),
    NOT_SONY(
        "Not a Sony device",
        "This USB device is not a Sony Xperia.",
    ),
    ;

    val isFlashable: Boolean get() = this == FLASHMODE
}
