package dev.flashtool.xperia.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.flashtool.xperia.core.FlashLog
import dev.flashtool.xperia.core.LogLevel

@Composable
fun LogScreen(viewModel: FlashViewModel) {
    val context = LocalContext.current
    val lines by viewModel.log.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var follow by remember { mutableStateOf(true) }
    var debug by remember { mutableStateOf(FlashLog.debugEnabled) }

    LaunchedEffect(lines.size, follow) {
        if (follow && lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Packets", style = MaterialTheme.typography.labelMedium)
            Switch(
                checked = debug,
                onCheckedChange = { debug = it; FlashLog.debugEnabled = it },
            )
            Text("Follow", style = MaterialTheme.typography.labelMedium)
            Switch(checked = follow, onCheckedChange = { follow = it })
            TextButton(onClick = { copyToClipboard(context, FlashLog.dump()) }) { Text("Copy") }
            TextButton(onClick = { viewModel.saveLog() }) { Text("Save") }
            TextButton(onClick = FlashLog::clear) { Text("Clear") }
        }

        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), state = listState) {
            items(lines) { line ->
                Text(
                    text = line.format(),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = when (line.level) {
                        LogLevel.ERROR -> MaterialTheme.colorScheme.error
                        LogLevel.WARN -> MaterialTheme.colorScheme.secondary
                        LogLevel.SUCCESS -> MaterialTheme.colorScheme.primary
                        LogLevel.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant
                        LogLevel.INFO -> MaterialTheme.colorScheme.onSurface
                    },
                )
            }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("flash log", text))
}
