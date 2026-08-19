package dev.flashtool.xperia.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.flashtool.xperia.usb.AttachedDevice

/**
 * Trim Area backup.
 *
 * TA unit numbers are not the same across models and Sony has never published them, so this
 * screen asks for the units to read rather than shipping a table of magic numbers that would be
 * wrong on half the devices.
 */
@Composable
fun TaScreen(ui: FirmwareUi, devices: List<AttachedDevice>, viewModel: FlashViewModel) {
    val target = devices.firstOrNull { it.mode.isFlashable }
    var partitions by remember { mutableStateOf("1, 2") }
    var range by remember { mutableStateOf("0x800-0x8FF") }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard(title = "Trim Area backup") {
            Text(
                "The TA partitions hold per-device data: DRM keys, the customisation id and the " +
                    "bootloader-unlock allowance. Back them up before flashing anything — some of it " +
                    "cannot be restored once it is gone.",
                style = MaterialTheme.typography.bodySmall,
            )
            LabelValue("Phone", target?.label ?: "not connected")
            LabelValue("Loader", ui.loader?.displayName ?: "open an FTF first")
            Text(
                "Reading the TA needs a loader running on the phone, so the loader from the open " +
                    "FTF is sent first. No images are written.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        OutlinedTextField(
            value = partitions,
            onValueChange = { partitions = it },
            label = { Text("TA partitions") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = range,
            onValueChange = { range = it },
            label = { Text("Units — e.g. 0x800-0x8FF, 0x921F") },
            modifier = Modifier.fillMaxWidth(),
        )

        Button(
            onClick = {
                viewModel.backupTa(
                    device = target,
                    partitions = parseNumbers(partitions).toList(),
                    units = parseUnits(range),
                )
            },
            enabled = target != null && ui.loader != null,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Read TA units") }

        Text(
            "Backups are written to ${viewModel.backupDir().absolutePath}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun parseNumbers(text: String): List<Int> =
    text.split(',', ' ', ';')
        .mapNotNull { it.trim().takeIf(String::isNotEmpty) }
        .mapNotNull { parseNumber(it) }

/** Accepts comma-separated units and `start-end` ranges, in decimal or 0x hex. */
internal fun parseUnits(text: String): List<Int> {
    val units = LinkedHashSet<Int>()
    for (part in text.split(',', ';')) {
        val token = part.trim()
        if (token.isEmpty()) continue
        val dash = token.indexOf('-', startIndex = 1)
        if (dash > 0) {
            val from = parseNumber(token.substring(0, dash).trim())
            val to = parseNumber(token.substring(dash + 1).trim())
            if (from != null && to != null && to >= from && to - from <= MAX_SCAN) {
                for (u in from..to) units += u
            }
        } else {
            parseNumber(token)?.let { units += it }
        }
    }
    return units.toList()
}

private fun parseNumber(token: String): Int? = when {
    token.startsWith("0x", ignoreCase = true) -> token.drop(2).toIntOrNull(16)
    else -> token.toIntOrNull()
}

private const val MAX_SCAN = 4096
