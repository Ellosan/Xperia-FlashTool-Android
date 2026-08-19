package dev.flashtool.xperia.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import dev.flashtool.xperia.core.FlashLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AttachedDevice(
    val device: UsbDevice,
    val mode: SonyMode,
    val hasPermission: Boolean,
) {
    val id: Int get() = device.deviceId
    val label: String
        get() = buildString {
            append(device.productName ?: "USB device")
            append(" · %04X:%04X".format(device.vendorId, device.productId))
        }
}

/**
 * Watches the USB bus for Sony devices and owns the permission dance. A single instance is
 * created by the Activity and released when it goes away.
 */
class UsbDeviceRepository(private val context: Context) {

    private val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    private val _devices = MutableStateFlow<List<AttachedDevice>>(emptyList())
    val devices: StateFlow<List<AttachedDevice>> = _devices.asStateFlow()

    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    val device = intent.usbDevice()
                    FlashLog.i(
                        if (granted) "USB permission granted for ${device?.deviceName}"
                        else "USB permission denied for ${device?.deviceName}",
                    )
                    refresh()
                }

                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    intent.usbDevice()?.let { FlashLog.i("Attached: ${it.deviceName} %04X:%04X".format(it.vendorId, it.productId)) }
                    refresh()
                }

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    intent.usbDevice()?.let { FlashLog.w("Detached: ${it.deviceName}") }
                    refresh()
                }
            }
        }
    }

    fun start() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
        registered = true
        refresh()
    }

    fun stop() {
        if (!registered) return
        runCatching { context.unregisterReceiver(receiver) }
        registered = false
    }

    fun refresh() {
        _devices.value = manager.deviceList.values
            .map { AttachedDevice(it, SonyUsb.modeOf(it), manager.hasPermission(it)) }
            .sortedByDescending { it.mode.isFlashable }
    }

    fun requestPermission(device: UsbDevice) {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val intent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
            flags,
        )
        manager.requestPermission(device, intent)
    }

    fun openTransport(device: UsbDevice): UsbS1Transport = UsbS1Transport.open(manager, device)

    fun hasPermission(device: UsbDevice): Boolean = manager.hasPermission(device)

    @Suppress("DEPRECATION")
    private fun Intent.usbDevice(): UsbDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }

    private companion object {
        const val ACTION_USB_PERMISSION = "dev.flashtool.xperia.USB_PERMISSION"
    }
}
