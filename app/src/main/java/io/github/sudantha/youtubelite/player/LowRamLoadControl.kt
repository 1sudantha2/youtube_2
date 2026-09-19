package io.github.sudantha.youtubelite.player

import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.upstream.DefaultAllocator

/** 20 MiB SAMPLE-buffer target, not a hard cap on codecs, surfaces, app RAM or process PSS. */
object LowRamLoadControl {
    const val TARGET_BYTES = 20 * 1024 * 1024
    fun create(): DefaultLoadControl = DefaultLoadControl.Builder()
        .setAllocator(DefaultAllocator(true, 64 * 1024))
        .setBufferDurationsMs(10_000, 25_000, 1_500, 3_000)
        .setTargetBufferBytes(TARGET_BYTES)
        // Byte threshold wins over the time target, rather than loading past 20 MiB for 10 s.
        .setPrioritizeTimeOverSizeThresholds(false)
        .setBackBuffer(0, false)
        .build()
}
