package io.github.sudantha.youtubelite.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val colors = darkColorScheme(
    primary = Color(0xFFFF5269), onPrimary = Color(0xFF32000B),
    primaryContainer = Color(0xFF51202A), secondary = Color(0xFFD2C4C7),
    background = Color(0xFF101014), surface = Color(0xFF101014),
    surfaceVariant = Color(0xFF24242C), onSurface = Color(0xFFF7F2F4),
)
@Composable
fun YouTubeTheme(content: @Composable () -> Unit) { MaterialTheme(colorScheme = colors, content = content) }
