package dev.flashtool.xperia.flash

import dev.flashtool.xperia.ftf.Ftf
import dev.flashtool.xperia.ftf.FtfImage
import dev.flashtool.xperia.ftf.FtfTaFile
import dev.flashtool.xperia.ftf.ImageCategory

/**
 * What the user asked for, resolved against what the FTF actually contains.
 *
 * Built once when the user hits Flash so the engine never has to consult the UI mid-run.
 */
data class FlashPlan(
    val ftf: Ftf,
    val images: List<FtfImage>,
    val taFiles: List<FtfTaFile>,
    val rebootWhenDone: Boolean,
) {
    val loader: FtfImage? get() = ftf.loader

    /** Payload bytes, loader included — the denominator for the progress bar. */
    val totalBytes: Long = (loader?.dataLength ?: 0L) + images.sumOf { it.dataLength }

    fun validate(): List<String> = buildList {
        if (loader == null) {
            add("This FTF contains no loader.sin. The phone's boot ROM will not accept any image without one.")
        }
        if (images.isEmpty() && taFiles.isEmpty()) {
            add("Nothing is selected to flash.")
        }
        if (images.any { it.category == ImageCategory.PARTITION } &&
            images.none { it.category == ImageCategory.BOOTLOADER } &&
            images.size > 1
        ) {
            add(
                "The partition table is selected but no bootloader image is. Repartitioning without " +
                    "rewriting the bootloader can leave the phone unable to boot.",
            )
        }
    }

    companion object {
        /**
         * The default Flashtool behaviour: everything except the partitions that only hold user
         * state, so an upgrade keeps apps and settings.
         */
        fun defaultSelection(ftf: Ftf): Set<String> =
            ftf.images.filterNot { it.category.isWipe }.map { it.entryName }.toSet()

        fun build(
            ftf: Ftf,
            selectedImages: Set<String>,
            selectedTa: Set<String>,
            rebootWhenDone: Boolean,
        ): FlashPlan = FlashPlan(
            ftf = ftf,
            images = ftf.images
                .filter { it.entryName in selectedImages }
                .sortedWith(compareBy({ it.category.order }, { it.displayName.lowercase() })),
            taFiles = ftf.taFiles.filter { it.entryName in selectedTa },
            rebootWhenDone = rebootWhenDone,
        )
    }
}
