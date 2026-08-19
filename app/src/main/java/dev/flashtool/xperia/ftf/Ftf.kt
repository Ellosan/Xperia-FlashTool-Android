package dev.flashtool.xperia.ftf

import dev.flashtool.xperia.core.FlashLog
import dev.flashtool.xperia.core.SeekableSource
import java.io.IOException
import java.io.InputStream

/**
 * Where an image sits in the flashing order. Getting this wrong is how phones end up bricked: the
 * partition table has to land before anything that is written into those partitions, and the
 * bootloader chain has to be consistent before the phone is allowed to reboot.
 */
enum class ImageCategory(val order: Int, val title: String) {
    LOADER(0, "Loader"),
    PARTITION(1, "Partition table"),
    BOOTLOADER(2, "Bootloader / trustzone"),
    MODEM(3, "Modem"),
    KERNEL(4, "Kernel / boot"),
    SYSTEM(5, "System / vendor"),
    FOTA(6, "Recovery / FOTA"),
    USERDATA(7, "User data"),
    OTHER(8, "Other"),
    ;

    /** Images that only carry user state; skipping them keeps apps and settings in place. */
    val isWipe: Boolean get() = this == USERDATA
}

data class FtfImage(
    val entryName: String,
    val displayName: String,
    val category: ImageCategory,
    /** Uncompressed size of the whole SIN, header included. */
    val totalSize: Long,
    val header: SinHeader?,
) {
    val dataLength: Long get() = totalSize - (header?.headerLength ?: 0)
    val partition: String get() = header?.partitionHint ?: displayName.removeSuffix(".sin")
}

data class FtfTaFile(
    val entryName: String,
    val displayName: String,
    val content: TaContent,
)

/**
 * A parsed FTF (Flash Tool Firmware) bundle: the ZIP Flashtool produces, holding a loader, the
 * partition images and any TA units that go with the firmware.
 */
class Ftf internal constructor(
    val name: String,
    private val archive: ZipArchive,
    val loader: FtfImage?,
    val images: List<FtfImage>,
    val taFiles: List<FtfTaFile>,
    val metadata: Map<String, String>,
) : AutoCloseable {

    /** Opens an image's payload, with the SIN header already skipped. */
    fun openImageData(image: FtfImage): InputStream {
        val entry = archive.entry(image.entryName)
            ?: throw IOException("${image.entryName} vanished from the archive")
        val stream = archive.open(entry)
        val skip = (image.header?.headerLength ?: 0).toLong()
        var skipped = 0L
        while (skipped < skip) {
            val n = stream.skip(skip - skipped)
            if (n <= 0) {
                // Some inflater streams refuse to skip; fall back to reading the header out.
                val discard = ByteArray(minOf(skip - skipped, 64 * 1024).toInt())
                val read = stream.read(discard)
                if (read < 0) throw IOException("${image.entryName} is shorter than its own header")
                skipped += read
            } else {
                skipped += n
            }
        }
        return stream
    }

    /** Re-reads an image's full SIN header, which is sent to the loader ahead of the payload. */
    fun readHeader(image: FtfImage): ByteArray {
        val header = image.header ?: return ByteArray(0)
        if (header.raw.size >= header.headerLength) return header.raw
        val entry = archive.entry(image.entryName)
            ?: throw IOException("${image.entryName} vanished from the archive")
        return archive.peek(entry, header.headerLength)
    }

    override fun close() = archive.close()

    companion object {

        fun open(name: String, source: SeekableSource): Ftf {
            val archive = ZipArchive(source)
            val images = ArrayList<FtfImage>()
            val taFiles = ArrayList<FtfTaFile>()
            val metadata = LinkedHashMap<String, String>()
            var loader: FtfImage? = null

            for (entry in archive.entries) {
                if (entry.isDirectory) continue
                val fileName = entry.fileName
                when {
                    fileName.endsWith(".sin", ignoreCase = true) -> {
                        val header = SinFile.parseHeader(
                            archive.peek(entry, SinFile.PREFIX_SIZE),
                            entry.size,
                        )
                        val image = FtfImage(
                            entryName = entry.name,
                            displayName = fileName,
                            category = categorise(fileName),
                            totalSize = entry.size,
                            header = header,
                        )
                        if (image.category == ImageCategory.LOADER) loader = image else images += image
                    }

                    fileName.endsWith(".ta", ignoreCase = true) -> {
                        runCatching {
                            val text = archive.open(entry).use { it.readBytes().toString(Charsets.US_ASCII) }
                            taFiles += FtfTaFile(entry.name, fileName, TaFile.parse(text))
                        }.onFailure { FlashLog.w("Ignoring unreadable TA file $fileName: ${it.message}") }
                    }

                    fileName.endsWith(".xml", ignoreCase = true) || fileName.endsWith(".txt", ignoreCase = true) -> {
                        runCatching {
                            val text = archive.open(entry).use { it.readBytes().toString(Charsets.UTF_8) }
                            metadata[fileName] = text.take(8 * 1024)
                        }
                    }
                }
            }

            images.sortWith(compareBy({ it.category.order }, { it.displayName.lowercase() }))
            taFiles.sortBy { it.displayName.lowercase() }

            if (loader == null) {
                FlashLog.w("This FTF has no loader.sin — the phone's boot ROM cannot be handed a loader")
            }
            FlashLog.i("Opened $name: ${images.size} images, ${taFiles.size} TA files")
            return Ftf(name, archive, loader, images, taFiles, metadata)
        }

        /** Maps a SIN file name onto its place in the flashing order. */
        fun categorise(fileName: String): ImageCategory {
            val n = fileName.lowercase().removeSuffix(".sin")
            return when {
                n.startsWith("loader") -> ImageCategory.LOADER
                n.startsWith("partition") || n.startsWith("gpt") || n.startsWith("ptable") -> ImageCategory.PARTITION
                n.startsWith("userdata") || n.startsWith("cache") || n.startsWith("appslog") ||
                    n.startsWith("diag") || n.startsWith("ldb") -> ImageCategory.USERDATA
                n.startsWith("fota") || n.startsWith("recovery") -> ImageCategory.FOTA
                n.startsWith("kernel") || n.startsWith("boot") || n.startsWith("ramdisk") ||
                    n.startsWith("dtbo") || n.startsWith("vbmeta") -> ImageCategory.KERNEL
                n.startsWith("amss") || n.startsWith("modem") || n.startsWith("nonhlos") ||
                    n.startsWith("fsg") || n.startsWith("dsp") || n.startsWith("adspso") -> ImageCategory.MODEM
                n.startsWith("aboot") || n.startsWith("sbl") || n.startsWith("xbl") || n.startsWith("tz") ||
                    n.startsWith("rpm") || n.startsWith("hyp") || n.startsWith("devcfg") ||
                    n.startsWith("cmnlib") || n.startsWith("keymaster") || n.startsWith("abl") ||
                    n.startsWith("s1sbl") || n.startsWith("bluetooth") -> ImageCategory.BOOTLOADER
                n.startsWith("system") || n.startsWith("vendor") || n.startsWith("product") ||
                    n.startsWith("oem") || n.startsWith("elabel") || n.startsWith("odm") ||
                    n.startsWith("apps_log") || n.startsWith("persist") -> ImageCategory.SYSTEM
                else -> ImageCategory.OTHER
            }
        }
    }
}
