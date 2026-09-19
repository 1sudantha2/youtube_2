package com.ultra.youtube.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Fixed palette. YouTube is dark-first; the light scheme exists only so the app does not
 * look broken if the user forces light mode.
 */
@Immutable
object AppColors {
    val YouTubeRed = Color(0xFFFF0000)
    val SurfaceDark = Color(0xFF0F0F0F)
    val SurfaceDarkElevated = Color(0xFF212121)
    val OutlineDark = Color(0xFF3F3F3F)
    val TextDark = Color(0xFFF1F1F1)
    val TextDarkSecondary = Color(0xFFAAAAAA)
    val Scrim = Color(0xB3000000)
    val Badge = Color(0xCC000000)

    val SurfaceLight = Color(0xFFFFFFFF)
    val SurfaceLightElevated = Color(0xFFF2F2F2)
    val TextLight = Color(0xFF0F0F0F)
    val TextLightSecondary = Color(0xFF606060)
}

private val DarkColors = darkColorScheme(
    primary = AppColors.YouTubeRed,
    onPrimary = Color.White,
    secondary = AppColors.SurfaceDarkElevated,
    onSecondary = AppColors.TextDark,
    background = AppColors.SurfaceDark,
    onBackground = AppColors.TextDark,
    surface = AppColors.SurfaceDark,
    onSurface = AppColors.TextDark,
    surfaceVariant = AppColors.SurfaceDarkElevated,
    onSurfaceVariant = AppColors.TextDarkSecondary,
    outline = AppColors.OutlineDark,
)

private val LightColors = lightColorScheme(
    primary = AppColors.YouTubeRed,
    onPrimary = Color.White,
    secondary = AppColors.SurfaceLightElevated,
    onSecondary = AppColors.TextLight,
    background = AppColors.SurfaceLight,
    onBackground = AppColors.TextLight,
    surface = AppColors.SurfaceLight,
    onSurface = AppColors.TextLight,
    surfaceVariant = AppColors.SurfaceLightElevated,
    onSurfaceVariant = AppColors.TextLightSecondary,
)

@Composable
fun YouTubeTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        content = content,
    )
}
