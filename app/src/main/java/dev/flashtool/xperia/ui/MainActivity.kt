package dev.flashtool.xperia.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {

    private val viewModel: FlashViewModel by viewModels()

    private val openFirmware = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            contentResolver.takePersistableUriPermission(it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            viewModel.openFirmware(it)
        }
    }

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            FlashtoolTheme {
                AppScaffold(
                    viewModel = viewModel,
                    // FTF files have no registered MIME type, so accept anything and let the
                    // archive reader reject files that are not firmware.
                    onPickFirmware = { openFirmware.launch(arrayOf("*/*")) },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.usb.refresh()
    }
}

private enum class Screen(val title: String) {
    FLASH("Flash"), TRIM_AREA("TA"), LOG("Log")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppScaffold(viewModel: FlashViewModel, onPickFirmware: () -> Unit) {
    var screen by rememberSaveable { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }

    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val flash by viewModel.flashState.collectAsStateWithLifecycle()
    val devices by viewModel.usb.devices.collectAsState()

    LaunchedEffect(ui.error, ui.notice) {
        val message = ui.error ?: ui.notice
        if (message != null) {
            snackbar.showSnackbar(message)
            viewModel.dismissMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = {
                Column {
                    Text("Xperia Flashtool")
                    Text(
                        ui.name ?: "No firmware loaded",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            })
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = screen) {
                Screen.entries.forEachIndexed { index, tab ->
                    Tab(
                        selected = screen == index,
                        onClick = { screen = index },
                        text = { Text(tab.title) },
                    )
                }
            }
            when (Screen.entries[screen]) {
                Screen.FLASH -> FlashScreen(
                    ui = ui,
                    flash = flash,
                    devices = devices,
                    viewModel = viewModel,
                    onPickFirmware = onPickFirmware,
                )

                Screen.TRIM_AREA -> TaScreen(ui = ui, devices = devices, viewModel = viewModel)
                Screen.LOG -> LogScreen(viewModel = viewModel)
            }
        }
    }
}
