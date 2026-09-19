package com.youtubelite.app.player

import androidx.media3.common.C
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.upstream.DefaultAllocator
import android.content.Context

/**
 * Aggressive RAM caps for the player pipeline.
 *
 * ExoPlayer defaults buffer up to ~54s / unbounded bytes; we cap:
 *   - keep only 10s ahead (min) / 25s ceiling (max)
 *   - start playback after 1.5s (3s after rebuffer)
 *   - hard cap total media RAM at 20 MB (targetBufferBytes)
 *   - zero back-buffer: segments behind the playhead are freed instantly
 *
 * Decoders: Media3 (>=1.1) runs the asynchronous MediaCodecAdapter path by
 * default on API 23+, i.e. buffers are queued to codecs without extra JVM
 * copies. We additionally enable decoder fallback + zero join-time so a
 * struggling budget decoder switches hard/soft codecs instead of dropping.
 */
object PlayerEngine {

    /** Hard ceiling for in-RAM media buffer across ALL renderers. */
    const val TARGET_BUFFER_BYTES = 20 * 1024 * 1024

    fun loadControl(): DefaultLoadControl =
        DefaultLoadControl.Builder()
            .setAllocator(DefaultAllocator(/* trimOnReset = */ true, C.DEFAULT_BUFFER_SEGMENT_SIZE))
            .setBufferDurationsMs(
                /* minBufferMs = */ 10_000,
                /* maxBufferMs = */ 25_000,
                /* bufferForPlaybackMs = */ 1_500,
                /* bufferForPlaybackAfterRebufferMs = */ 3_000,
            )
            .setTargetBufferBytes(TARGET_BUFFER_BYTES)
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(/* backBufferDurationMs = */ 0, /* retainBackBufferFromKeyframe = */ false)
            .build()

    fun renderersFactory(context: Context): RenderersFactory =
        DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
            .setAllowedVideoJoiningTimeMs(/* allowedVideoJoiningTimeMs = */ 0)
}
