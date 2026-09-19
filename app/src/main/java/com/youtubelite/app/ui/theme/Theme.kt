package com.youtubelite.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Fixed dark scheme: no dynamic-color fetch, no system theme queries,
// consistent first frame across every device.
private val DarkScheme = darkColorScheme(
    primary = Color(0xFFFF0033),
    onPrimary = Color.White,
    secondary = Color(0xFFB8B8B8),
    background = Color(0xFF0F0F0F),
    onBackground = Color(0xFFF1F1F1),
    surface = Color(0xFF181818),
    onSurface = Color(0xFFF1F1F1),
    surfaceVariant = Color(0xFF272727),
    onSurfaceVariant = Color(0xFFB0B0B0),
    surfaceContainer = Color(0xFF212121),
    surfaceContainerHigh = Color(0xFF2A2A2A),
)

@Composable
fun YouTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkScheme,
        content = content,
    )
}
