package app.you.tube.player

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.upstream.DefaultAllocator

/**
 * Aggressive RAM-capping LoadControl — replaces ExoPlayer's defaults
 * (min/max = 50s each, ~50-100 MB+ effective buffer).
 *
 *  ┌────────────────────────────── Buffer policy ──────────────────────────────┐
 *  │ minBufferMs                        10,000 ms (10 s floor: keep-alive)      │
 *  │ maxBufferMs                        25,000 ms (25 s ceiling)               │
 *  │ bufferForPlaybackMs                 1,500 ms (1.5 s to start playback)     │
 *  │ bufferForPlaybackAfterRebufferMs    3,000 ms (3 s to resume after rebuffer)│
 *  │ targetBufferBytes                     20 MB (hard byte cap)               │
 *  │ prioritizeTimeOverSizeThresholds       false (byte cap is enforced)       │
 *  │ backBuffer                             5 s (short seek-back window)       │
 *  └───────────────────────────────────────────────────────────────────────────┘
 *
 * With prioritizeTimeOverSizeThresholds=false, DefaultLoadControl stops
 * buffering as soon as either 25 s of media OR 20 MB of allocator memory is
 * reached, and refills once buffered duration drops below 10 s — exactly the
 * low-RAM envelope required, while 1.5 s/3 s start thresholds keep startup
 * snappy. The 64 KB-segment DefaultAllocator trims on resets so released
 * segments are returned between quality switches.
 */
@UnstableApi
class LowRamLoadControl : DefaultLoadControl(
    /* allocator = */ DefaultAllocator(/* trimOnReset = */ true, C.DEFAULT_BUFFER_SEGMENT_SIZE),
    /* minBufferMs = */ MIN_BUFFER_MS,
    /* maxBufferMs = */ MAX_BUFFER_MS,
    /* bufferForPlaybackMs = */ BUFFER_FOR_PLAYBACK_MS,
    /* bufferForPlaybackAfterRebufferMs = */ BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
    /* targetBufferBytes = */ TARGET_BUFFER_BYTES,
    /* prioritizeTimeOverSizeThresholds = */ false,
    /* backBufferDurationMs = */ BACK_BUFFER_MS,
    /* retainBackBufferFromKeyframe = */ false
) {
    companion object {
        const val MIN_BUFFER_MS = 10_000
        const val MAX_BUFFER_MS = 25_000
        const val BUFFER_FOR_PLAYBACK_MS = 1_500
        const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 3_000
        const val TARGET_BUFFER_BYTES = 20 * 1024 * 1024
        const val BACK_BUFFER_MS = 5_000
    }
}
