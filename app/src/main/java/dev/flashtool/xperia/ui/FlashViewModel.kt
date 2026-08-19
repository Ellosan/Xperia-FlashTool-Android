package dev.flashtool.xperia.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.flashtool.xperia.core.ContentUriSource
import dev.flashtool.xperia.core.FlashLog
import dev.flashtool.xperia.core.displayNameOf
import dev.flashtool.xperia.core.humanSize
import dev.flashtool.xperia.flash.FlashPlan
import dev.flashtool.xperia.flash.Flasher
import dev.flashtool.xperia.ftf.Ftf
import dev.flashtool.xperia.ftf.FtfImage
import dev.flashtool.xperia.ftf.FtfTaFile
import dev.flashtool.xperia.ftf.TaContent
import dev.flashtool.xperia.ftf.TaFile
import dev.flashtool.xperia.ftf.TaUnit
import dev.flashtool.xperia.s1.LoopbackS1Transport
import dev.flashtool.xperia.s1.S1Session
import dev.flashtool.xperia.s1.S1Transport
import dev.flashtool.xperia.service.FlashService
import dev.flashtool.xperia.usb.AttachedDevice
import dev.flashtool.xperia.usb.UsbDeviceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class FirmwareUi(
    val name: String? = null,
    val loading: Boolean = false,
    val loader: FtfImage? = null,
    val images: List<FtfImage> = emptyList(),
    val taFiles: List<FtfTaFile> = emptyList(),
    val selectedImages: Set<String> = emptySet(),
    val selectedTa: Set<String> = emptySet(),
    val rebootWhenDone: Boolean = true,
    val dryRun: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
) {
    val isLoaded: Boolean get() = name != null && !loading
    val selectedBytes: Long get() = images.filter { it.entryName in selectedImages }.sumOf { it.dataLength }
    val summary: String
        get() = "${selectedImages.size} of ${images.size} images · ${humanSize(selectedBytes)}"
}

class FlashViewModel(app: Application) : AndroidViewModel(app) {

    val usb = UsbDeviceRepository(app)
    val flashState = Flasher.engine.state
    val log = FlashLog.lines

    private val _ui = MutableStateFlow(FirmwareUi())
    val ui: StateFlow<FirmwareUi> = _ui.asStateFlow()

    private var ftf: Ftf? = null

    init {
        usb.start()
    }

    // ------------------------------------------------------------ firmware

    fun openFirmware(uri: Uri) {
        val context = getApplication<Application>()
        _ui.value = FirmwareUi(loading = true, dryRun = _ui.value.dryRun)
        viewModelScope.launch {
            try {
                val name = context.displayNameOf(uri)
                val opened = withContext(Dispatchers.IO) {
                    Ftf.open(name, ContentUriSource(context, uri))
                }
                ftf?.close()
                ftf = opened
                _ui.value = FirmwareUi(
                    name = name,
                    loader = opened.loader,
                    images = opened.images,
                    taFiles = opened.taFiles,
                    selectedImages = FlashPlan.defaultSelection(opened),
                    selectedTa = opened.taFiles.map { it.entryName }.toSet(),
                    dryRun = _ui.value.dryRun,
                    notice = if (opened.loader == null) {
                        "No loader.sin in this FTF — it cannot be flashed on its own."
                    } else {
                        null
                    },
                )
                FlashLog.i("Loaded $name (${opened.images.size} images, ${humanSize(opened.images.sumOf { it.dataLength })})")
            } catch (e: Throwable) {
                FlashLog.e("Could not read the firmware file", e)
                _ui.value = FirmwareUi(error = e.message ?: "Could not read the firmware file")
            }
        }
    }

    fun toggleImage(image: FtfImage) = _ui.update {
        copy(
            selectedImages = if (image.entryName in selectedImages) {
                selectedImages - image.entryName
            } else {
                selectedImages + image.entryName
            },
        )
    }

    fun toggleTa(taFile: FtfTaFile) = _ui.update {
        copy(
            selectedTa = if (taFile.entryName in selectedTa) selectedTa - taFile.entryName else selectedTa + taFile.entryName,
        )
    }

    fun selectAll() = _ui.update { copy(selectedImages = images.map { it.entryName }.toSet()) }

    fun selectNone() = _ui.update { copy(selectedImages = emptySet()) }

    fun selectDefault() = _ui.update {
        copy(selectedImages = images.filterNot { it.category.isWipe }.map { it.entryName }.toSet())
    }

    fun setReboot(value: Boolean) = _ui.update { copy(rebootWhenDone = value) }

    fun setDryRun(value: Boolean) = _ui.update { copy(dryRun = value) }

    fun dismissMessage() = _ui.update { copy(error = null, notice = null) }

    /** Problems worth showing the user before they commit to a flash. */
    fun warnings(): List<String> {
        val current = ftf ?: return listOf("No firmware file is open.")
        return buildPlan(current).validate()
    }

    // ------------------------------------------------------------- flashing

    fun startFlash(device: AttachedDevice?) {
        val current = ftf ?: return
        if (Flasher.isRunning) return

        val plan = buildPlan(current)
        val dryRun = _ui.value.dryRun

        val transport: S1Transport = if (dryRun) {
            LoopbackS1Transport()
        } else {
            if (device == null) {
                _ui.update { copy(error = "No phone in flash mode is connected.") }
                return
            }
            if (!device.mode.isFlashable) {
                _ui.update { copy(error = device.mode.hint) }
                return
            }
            try {
                usb.openTransport(device.device)
            } catch (e: Throwable) {
                FlashLog.e("Could not open the USB link", e)
                _ui.update { copy(error = e.message ?: "Could not open the USB link") }
                return
            }
        }

        val context = getApplication<Application>()
        FlashService.start(context)
        Flasher.start(plan, transport, dryRun) { FlashService.stop(context) }
    }

    fun cancelFlash() = Flasher.cancel()

    fun resetFlashState() = Flasher.engine.reset()

    private fun buildPlan(current: Ftf) = FlashPlan.build(
        ftf = current,
        selectedImages = _ui.value.selectedImages,
        selectedTa = _ui.value.selectedTa,
        rebootWhenDone = _ui.value.rebootWhenDone,
    )

    // ------------------------------------------------------------------ TA

    /**
     * Reads TA units off the phone and writes them to a `.ta` file in the app's files directory.
     *
     * The boot ROM cannot serve TA requests on its own, so the loader from the currently open FTF
     * is sent first — exactly what happens at the start of a flash, minus the images.
     */
    fun backupTa(device: AttachedDevice?, partitions: List<Int>, units: List<Int>) {
        val current = ftf
        if (current?.loader == null) {
            _ui.update { copy(error = "Open an FTF first — its loader.sin is needed to talk to the TA.") }
            return
        }
        if (device == null || !device.mode.isFlashable) {
            _ui.update { copy(error = "Connect a phone in flash mode first.") }
            return
        }
        if (units.isEmpty()) {
            _ui.update { copy(error = "No TA units requested.") }
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val transport = usb.openTransport(device.device)
                S1Session(transport).use { session ->
                    val loader = current.loader
                    current.openImageData(loader).use { data ->
                        session.sendLoader(current.readHeader(loader), data, loader.dataLength)
                    }

                    val written = ArrayList<File>()
                    for (partition in partitions) {
                        val read = ArrayList<TaUnit>()
                        session.openTa(partition)
                        try {
                            for (unit in units) {
                                runCatching { session.readTaUnit(unit) }
                                    .onSuccess { value ->
                                        if (value.isNotEmpty()) {
                                            read += TaUnit(unit, value)
                                            FlashLog.i("TA %08X = ${value.size} bytes".format(unit))
                                        }
                                    }
                                    .onFailure { FlashLog.d("TA %08X not present".format(unit)) }
                            }
                        } finally {
                            session.closeTa()
                        }

                        if (read.isNotEmpty()) {
                            val file = File(backupDir(), "ta-partition-$partition-${System.currentTimeMillis()}.ta")
                            file.writeText(TaFile.write(TaContent(partition, read)))
                            written += file
                            FlashLog.ok("Saved ${read.size} units to ${file.name}")
                        } else {
                            FlashLog.w("Partition $partition returned no units")
                        }
                    }
                    session.endSession()

                    _ui.update {
                        copy(
                            notice = if (written.isEmpty()) {
                                "No TA units could be read."
                            } else {
                                "TA backup saved to ${written.joinToString { it.name }}"
                            },
                        )
                    }
                }
            } catch (e: Throwable) {
                FlashLog.e("TA backup failed", e)
                _ui.update { copy(error = e.message ?: "TA backup failed") }
            }
        }
    }

    fun backupDir(): File =
        File(getApplication<Application>().getExternalFilesDir(null) ?: getApplication<Application>().filesDir, "ta")
            .apply { mkdirs() }

    fun saveLog(): File? = runCatching {
        val dir = File(getApplication<Application>().getExternalFilesDir(null) ?: getApplication<Application>().filesDir, "logs")
        dir.mkdirs()
        File(dir, "flash-${System.currentTimeMillis()}.log").apply { writeText(FlashLog.dump()) }
    }.onFailure { FlashLog.e("Could not save the log", it) }.getOrNull()

    override fun onCleared() {
        usb.stop()
        if (!Flasher.isRunning) ftf?.close()
        super.onCleared()
    }
}

private inline fun MutableStateFlow<FirmwareUi>.update(block: FirmwareUi.() -> FirmwareUi) {
    value = value.block()
}
