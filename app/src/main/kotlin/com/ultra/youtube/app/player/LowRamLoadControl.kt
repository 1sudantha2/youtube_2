package com.ultra.youtube.app.player

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl

/**
 * The memory ceiling of the whole app.
 *
 * ExoPlayer's default [DefaultLoadControl] will happily hold ~50 s of media (~75 MB of
 * heap for a 1080p stream) before it stops loading. On a low-end device that is most of
 * the budget, and it is what makes a video app get killed in the background.
 *
 * This control clamps the buffer window to **10 s – 25 s** and the *allocated* buffer
 * memory to **20 MB**, whichever is hit first:
 *
 *  - [MIN_BUFFER_MS] / [MAX_BUFFER_MS]  — time window ExoPlayer keeps filled
 *  - [BUFFER_FOR_PLAYBACK_MS]           — start playing after 1 s buffered (fast start)
 *  - [BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS] — wait 2 s after a stall (fewer re-stalls)
 *  - `setTargetBufferBytes`             — hard 20 MB ceiling on [androidx.media3.common.util.Allocator]
 *
 * Allocation happens in 64 KB blocks, so the real high-water mark is
 * `ceil(20 MB / 64 KB) * 64 KB` = 20 MB exactly, plus a few blocks in flight.
 */
@UnstableApi
object LowRamLoadControl {

    /** Lower bound of the buffer window: never let the player sit on less than this. */
    const val MIN_BUFFER_MS = 10_000

    /** Upper bound of the buffer window: never buffer more than this far ahead. */
    const val MAX_BUFFER_MS = 25_000

    /** Start playback as soon as 1 s is buffered. */
    const val BUFFER_FOR_PLAYBACK_MS = 1_000

    /** After a rebuffer, wait for 2 s before resuming. */
    const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 2_000

    /** Hard ceiling on buffer memory: 20 MB. */
    const val MAX_BUFFER_BYTES = 20 * 1024 * 1024

    /**
     * Playback speed above which the time window is scaled up, so 2x playback does not
     * starve. ExoPlayer multiplies the window by the speed, so we pre-compensate.
     */
    const val MAX_PLAYBACK_SPEED = 2.0f

    fun build(): DefaultLoadControl = DefaultLoadControl.Builder()
        .setAllocator(
            androidx.media3.common.util.DefaultAllocator(
                /* trimOnReset= */ true,
                C.DEFAULT_BUFFER_SEGMENT_SIZE,
            ),
        )
        .setBufferDurationsMs(
            MIN_BUFFER_MS,
            MAX_BUFFER_MS,
            BUFFER_FOR_PLAYBACK_MS,
            BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
        )
        // Overrides the duration-derived target: even a high-bitrate 1080p60 stream can
        // never allocate more than 20 MB of media.
        .setTargetBufferBytes(MAX_BUFFER_BYTES)
        .setPrioritizeTimeOverSizeThresholds(false)
        .setBackBuffer(
            /* backBufferDurationMs= */ 0,
            /* retainBackBufferFromKeyframe= */ false,
        )
        .build()

    /**
     * Effective ceiling in bytes for a given playback speed. Exposed for tests and for the
     * HUD readout.
     */
    fun effectiveByteCeiling(playbackSpeed: Float = 1f): Int =
        MAX_BUFFER_BYTES.coerceAtMost(
            (MAX_BUFFER_BYTES * playbackSpeed.coerceIn(1f, MAX_PLAYBACK_SPEED)).toInt(),
        )
}
