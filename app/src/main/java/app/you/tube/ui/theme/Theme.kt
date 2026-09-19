package app.you.tube.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// YouTube-inspired palette
private val BrandRed = Color(0xFFFF0033)
private val DarkBackground = Color(0xFF0F0F0F)
private val DarkSurfaceContainer = Color(0xFF212121)
private val DarkSurfaceVariant = Color(0xFF272727)
private val DarkTextPrimary = Color(0xFFF1F1F1)
private val DarkTextSecondary = Color(0xFFAAAAAA)

private val LightBackground = Color(0xFFFFFFFF)
private val LightSurfaceContainer = Color(0xFFF2F2F2)
private val LightSurfaceVariant = Color(0xFFE5E5E5)
private val LightTextPrimary = Color(0xFF0F0F0F)
private val LightTextSecondary = Color(0xFF606060)

private val YouDarkColors = darkColorScheme(
    primary = BrandRed,
    onPrimary = Color.White,
    primaryContainer = DarkSurfaceVariant,
    onPrimaryContainer = DarkTextPrimary,
    secondary = DarkTextPrimary,
    onSecondary = DarkBackground,
    background = DarkBackground,
    onBackground = DarkTextPrimary,
    surface = DarkBackground,
    onSurface = DarkTextPrimary,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkTextSecondary,
    surfaceContainer = DarkSurfaceContainer,
    surfaceContainerHigh = DarkSurfaceVariant,
    error = Color(0xFFFF5C5C),
    onError = Color.Black
)

private val YouLightColors = lightColorScheme(
    primary = BrandRed,
    onPrimary = Color.White,
    background = LightBackground,
    onBackground = LightTextPrimary,
    surface = LightBackground,
    onSurface = LightTextPrimary,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightTextSecondary,
    surfaceContainer = LightSurfaceContainer,
    surfaceContainerHigh = LightSurfaceVariant,
    error = Color(0xFFCC0000),
    onError = Color.White
)

/**
 * Deterministic Material 3 theme (dynamic color disabled on purpose: stable
 * palette = no runtime color churn, and the YouTube-brand red stays put).
 */
@Composable
fun YouTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) YouDarkColors else YouLightColors,
        content = content
    )
}
