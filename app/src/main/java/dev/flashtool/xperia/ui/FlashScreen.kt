package dev.flashtool.xperia.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.flashtool.xperia.core.humanSize
import dev.flashtool.xperia.flash.FlashPhase
import dev.flashtool.xperia.flash.FlashState
import dev.flashtool.xperia.ftf.FtfImage
import dev.flashtool.xperia.ftf.ImageCategory
import dev.flashtool.xperia.usb.AttachedDevice
import dev.flashtool.xperia.usb.SonyMode

@Composable
fun FlashScreen(
    ui: FirmwareUi,
    flash: FlashState,
    devices: List<AttachedDevice>,
    viewModel: FlashViewModel,
    onPickFirmware: () -> Unit,
) {
    val target = devices.firstOrNull { it.mode.isFlashable }
    var confirming by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { DeviceCard(devices, viewModel) }
        item { FirmwareCard(ui, onPickFirmware) }

        if (flash.phase != FlashPhase.IDLE) {
            item { ProgressCard(flash, viewModel) }
        }

        if (ui.isLoaded) {
            item { OptionsCard(ui, viewModel) }
            item {
                SectionCard(
                    title = "Images",
                    trailing = {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = viewModel::selectDefault) { Text("Default") }
                            TextButton(onClick = viewModel::selectAll) { Text("All") }
                            TextButton(onClick = viewModel::selectNone) { Text("None") }
                        }
                    },
                ) {
                    Text(ui.summary, style = MaterialTheme.typography.bodySmall)
                }
            }

            items(ui.images, key = { it.entryName }) { image ->
                ImageRow(
                    image = image,
                    checked = image.entryName in ui.selectedImages,
                    enabled = !flash.phase.isRunning,
                    onToggle = { viewModel.toggleImage(image) },
                )
            }

            if (ui.taFiles.isNotEmpty()) {
                item {
                    SectionCard(title = "TA units in this firmware") {
                        Text(
                            "TA units carry per-device settings such as the customisation id. " +
                                "Leave them enabled unless you know why you are skipping them.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                items(ui.taFiles, key = { it.entryName }) { taFile ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = taFile.entryName in ui.selectedTa,
                            onCheckedChange = { viewModel.toggleTa(taFile) },
                            enabled = !flash.phase.isRunning,
                        )
                        Column(Modifier.weight(1f)) {
                            Text(taFile.displayName, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "partition ${taFile.content.partition} · ${taFile.content.units.size} units",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            item {
                Button(
                    onClick = { confirming = true },
                    enabled = !flash.phase.isRunning && (ui.dryRun || target != null),
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (ui.dryRun) {
                            MaterialTheme.colorScheme.secondary
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    ),
                ) {
                    Text(
                        when {
                            flash.phase.isRunning -> "Flashing…"
                            ui.dryRun -> "Run dry run"
                            target == null -> "Waiting for a phone in flash mode"
                            else -> "Flash"
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
    }

    if (confirming) {
        val warnings = viewModel.warnings()
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(if (ui.dryRun) "Start dry run?" else "Flash this phone?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${ui.selectedImages.size} images · ${humanSize(ui.selectedBytes)}")
                    if (!ui.dryRun) {
                        Text(
                            "Do not unplug the cable and do not let the phone or this device run out " +
                                "of battery until this finishes. An interrupted flash usually leaves " +
                                "the phone unable to boot.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    warnings.forEach {
                        Text("• $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    viewModel.startFlash(target)
                }) { Text(if (ui.dryRun) "Start" else "Flash") }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun DeviceCard(devices: List<AttachedDevice>, viewModel: FlashViewModel) {
    SectionCard(
        title = "Phone",
        trailing = { TextButton(onClick = viewModel.usb::refresh) { Text("Refresh") } },
    ) {
        if (devices.isEmpty()) {
            Text("Nothing connected.", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Power the Xperia off completely, hold VOLUME DOWN, then connect it to this phone " +
                    "with an OTG cable. The notification LED turns green in flash mode.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }

        devices.forEach { device ->
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(device.label, style = MaterialTheme.typography.bodyMedium)
                Text(
                    device.mode.title,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (device.mode.isFlashable) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                if (device.mode != SonyMode.FLASHMODE) {
                    Text(device.mode.hint, style = MaterialTheme.typography.bodySmall)
                }
                if (device.mode != SonyMode.NOT_SONY && !device.hasPermission) {
                    OutlinedButton(onClick = { viewModel.usb.requestPermission(device.device) }) {
                        Text("Grant USB access")
                    }
                }
            }
        }
    }
}

@Composable
private fun FirmwareCard(ui: FirmwareUi, onPickFirmware: () -> Unit) {
    SectionCard(
        title = "Firmware",
        trailing = { TextButton(onClick = onPickFirmware) { Text(if (ui.isLoaded) "Change" else "Open FTF") } },
    ) {
        when {
            ui.loading -> Text("Reading archive…")
            ui.name == null -> Text(
                "Pick an .ftf file. Nothing is copied — it is read straight from storage.",
                style = MaterialTheme.typography.bodySmall,
            )

            else -> {
                LabelValue("File", ui.name)
                LabelValue("Loader", ui.loader?.displayName ?: "missing")
                LabelValue("Images", "${ui.images.size}")
                LabelValue("TA files", "${ui.taFiles.size}")
            }
        }
    }
}

@Composable
private fun OptionsCard(ui: FirmwareUi, viewModel: FlashViewModel) {
    SectionCard(title = "Options") {
        ToggleRow(
            title = "Reboot when finished",
            subtitle = "Leave off to stay in flash mode for a second pass.",
            checked = ui.rebootWhenDone,
            onChange = viewModel::setReboot,
        )
        ToggleRow(
            title = "Dry run",
            subtitle = "Walk the whole flash against a simulated loader. Nothing is sent over USB.",
            checked = ui.dryRun,
            onChange = viewModel::setDryRun,
        )
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ProgressCard(flash: FlashState, viewModel: FlashViewModel) {
    SectionCard(
        title = if (flash.dryRun) "${flash.phase.title} (dry run)" else flash.phase.title,
        trailing = {
            when {
                flash.phase.isRunning -> TextButton(onClick = viewModel::cancelFlash) { Text("Cancel") }
                else -> TextButton(onClick = viewModel::resetFlashState) { Text("Dismiss") }
            }
        },
    ) {
        if (flash.currentItem.isNotEmpty()) {
            Text(flash.currentItem, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            LinearProgressIndicator(
                progress = { flash.currentFraction },
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
            )
        }
        LinearProgressIndicator(
            progress = { flash.overallFraction },
            modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)),
        )
        LabelValue(
            "Transferred",
            "${humanSize(flash.doneBytes + flash.currentBytes)} of ${humanSize(flash.totalBytes)}",
        )
        LabelValue("Speed", flash.throughputText)
        LabelValue("Remaining", flash.etaText)
        flash.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        if (flash.phase == FlashPhase.DONE) {
            Text(
                "Finished in ${flash.elapsedMs / 1000}s.",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun ImageRow(image: FtfImage, checked: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() }, enabled = enabled)
        Column(Modifier.weight(1f)) {
            Text(image.displayName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CategoryChip(image.category)
                Text(
                    humanSize(image.dataLength) +
                        (image.header?.let { " · SIN v${it.version}" } ?: " · raw"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@Composable
private fun CategoryChip(category: ImageCategory) {
    val color = when (category) {
        ImageCategory.PARTITION, ImageCategory.BOOTLOADER -> MaterialTheme.colorScheme.error
        ImageCategory.USERDATA -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.primary
    }
    Box(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.18f))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        Text(category.title, style = MaterialTheme.typography.labelSmall, color = color)
    }
}
