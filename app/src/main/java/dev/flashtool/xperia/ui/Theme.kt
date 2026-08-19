package dev.flashtool.xperia.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Accent = Color(0xFF4FC3F7)
private val AccentDark = Color(0xFF0277BD)

private val DarkColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF00243A),
    secondary = Color(0xFFFFD54F),
    background = Color(0xFF0B1420),
    surface = Color(0xFF12202E),
    surfaceVariant = Color(0xFF1B2C3D),
    error = Color(0xFFFF8A80),
)

private val LightColors = lightColorScheme(
    primary = AccentDark,
    secondary = Color(0xFFF9A825),
)

@Composable
fun FlashtoolTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
