package com.ultra.youtube.app.player

import androidx.compose.runtime.Immutable

/**
 * Player state exposed to Compose.
 *
 * `@Immutable` + a single `copy()` per player event means the player overlay recomposes
 * exactly once per real change; nothing here is a mutable collection, which is what lets
 * Compose skip the whole subtree otherwise.
 */
@Immutable
data class PlayerState(
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val isEnded: Boolean = false,
    val positionMs: Long = 0L,
    val bufferedMs: Long = 0L,
    val durationMs: Long = 0L,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val rotationDegrees: Int = 0,
    val pixelWidthHeightRatio: Float = 1f,
    val selectedHeight: Int = 0,
    val maxTrackHeight: Int = 0,
    val isAutoQuality: Boolean = true,
    val error: String? = null,
) {
    val hasVideo: Boolean get() = videoWidth > 0 && videoHeight > 0

    /** Aspect ratio to lay the surface out at, or 16:9 before the first frame arrives. */
    val aspectRatio: Float
        get() = if (hasVideo) videoWidth * pixelWidthHeightRatio / videoHeight else 16f / 9f

    /** `0f..1f` progress, safe when the duration is still unknown. */
    val progress: Float
        get() = if (durationMs <= 0L) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)

    val bufferedFraction: Float
        get() = if (durationMs <= 0L) 0f else (bufferedMs.toFloat() / durationMs).coerceIn(0f, 1f)

    val qualityLabel: String
        get() = if (isAutoQuality) "Auto" else if (selectedHeight > 0) "${selectedHeight}p" else "Auto"
}
