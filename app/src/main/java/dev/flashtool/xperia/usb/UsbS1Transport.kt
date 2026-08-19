package dev.flashtool.xperia.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import dev.flashtool.xperia.core.FlashLog
import dev.flashtool.xperia.s1.S1IoException
import dev.flashtool.xperia.s1.S1Transport

/**
 * [S1Transport] over a USB-OTG bulk pipe.
 *
 * The phone exposes a single vendor-specific interface with one bulk IN and one bulk OUT
 * endpoint; everything is plain bulk traffic with no control requests once the interface is
 * claimed.
 */
class UsbS1Transport private constructor(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val inEndpoint: UsbEndpoint,
    private val outEndpoint: UsbEndpoint,
    device: UsbDevice,
) : S1Transport {

    override val description: String =
        "${device.manufacturerName ?: "Sony"} ${device.productName ?: "Xperia"} " +
            "(%04X:%04X)".format(device.vendorId, device.productId)

    private val maxTransfer = maxOf(inEndpoint.maxPacketSize, outEndpoint.maxPacketSize)
        .coerceAtLeast(512)

    override fun write(data: ByteArray, offset: Int, length: Int, timeoutMs: Int) {
        var written = 0
        while (written < length) {
            // bulkTransfer's offset overload exists on all supported API levels (18+).
            val n = connection.bulkTransfer(
                outEndpoint,
                data,
                offset + written,
                minOf(length - written, MAX_BULK_CHUNK),
                timeoutMs,
            )
            if (n < 0) throw S1IoException("USB write failed after $written/$length bytes")
            if (n == 0) throw S1IoException("USB write stalled after $written/$length bytes")
            written += n
        }
        // A transfer that is an exact multiple of the endpoint size needs a zero-length packet so
        // the loader knows the message ended; without it the phone waits forever on large chunks.
        if (length > 0 && length % maxTransfer == 0) {
            connection.bulkTransfer(outEndpoint, ByteArray(0), 0, 0, timeoutMs)
        }
    }

    override fun read(dest: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
        val n = connection.bulkTransfer(inEndpoint, dest, offset, minOf(length, MAX_BULK_CHUNK), timeoutMs)
        if (n < 0) throw S1IoException("USB read failed (timeout ${timeoutMs}ms)")
        return n
    }

    override fun close() {
        runCatching { connection.releaseInterface(usbInterface) }
        runCatching { connection.close() }
        FlashLog.i("USB link to $description closed")
    }

    companion object {
        /** Android's bulkTransfer tops out around 16 KiB per call on some kernels; stay under it. */
        private const val MAX_BULK_CHUNK = 16 * 1024

        /**
         * Claims the device's bulk interface. The caller must already hold USB permission for it.
         */
        fun open(manager: UsbManager, device: UsbDevice): UsbS1Transport {
            val candidate = (0 until device.interfaceCount)
                .map { device.getInterface(it) }
                .firstNotNullOfOrNull { iface -> endpointsOf(iface)?.let { iface to it } }
                ?: throw S1IoException("No bulk in/out endpoint pair on ${device.deviceName}")

            val (usbInterface, endpoints) = candidate
            val connection = manager.openDevice(device)
                ?: throw S1IoException("Could not open ${device.deviceName} — USB permission may have been revoked")

            if (!connection.claimInterface(usbInterface, true)) {
                connection.close()
                throw S1IoException("Another process is holding the phone's USB interface")
            }

            FlashLog.i(
                "Claimed interface ${usbInterface.id} (class 0x%02X), bulk in=0x%02X out=0x%02X, max packet ${endpoints.first.maxPacketSize}"
                    .format(usbInterface.interfaceClass, endpoints.first.address, endpoints.second.address),
            )
            return UsbS1Transport(connection, usbInterface, endpoints.first, endpoints.second, device)
        }

        private fun endpointsOf(iface: UsbInterface): Pair<UsbEndpoint, UsbEndpoint>? {
            var bulkIn: UsbEndpoint? = null
            var bulkOut: UsbEndpoint? = null
            for (i in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(i)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.direction == UsbConstants.USB_DIR_IN && bulkIn == null) bulkIn = ep
                if (ep.direction == UsbConstants.USB_DIR_OUT && bulkOut == null) bulkOut = ep
            }
            return if (bulkIn != null && bulkOut != null) bulkIn to bulkOut else null
        }
    }
}
