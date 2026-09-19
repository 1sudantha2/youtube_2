package com.ultra.youtube.app

import com.ultra.youtube.app.player.LowRamLoadControl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The RAM contract.
 *
 * `LowRamLoadControl.build()` itself needs the media3 classes, but the *numbers* it is built
 * from are the contract this app is judged on, so they are pinned here. A future edit that
 * silently raises the buffer window fails this test instead of shipping.
 */
class LowRamLoadControlTest {

    @Test
    fun `buffer window is 10 to 25 seconds`() {
        assertEquals(10_000, LowRamLoadControl.MIN_BUFFER_MS)
        assertEquals(25_000, LowRamLoadControl.MAX_BUFFER_MS)
        assertTrue(
            "min must be below max or ExoPlayer throws",
            LowRamLoadControl.MIN_BUFFER_MS < LowRamLoadControl.MAX_BUFFER_MS,
        )
    }

    @Test
    fun `buffer memory ceiling is 20 MB`() {
        assertEquals(20 * 1024 * 1024, LowRamLoadControl.MAX_BUFFER_BYTES)
    }

    @Test
    fun `playback thresholds stay inside the window`() {
        assertTrue(LowRamLoadControl.BUFFER_FOR_PLAYBACK_MS <= LowRamLoadControl.MIN_BUFFER_MS)
        assertTrue(
            LowRamLoadControl.BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS <= LowRamLoadControl.MIN_BUFFER_MS,
        )
    }

    @Test
    fun `the byte ceiling never grows past 20 MB, even at 2x speed`() {
        assertEquals(20 * 1024 * 1024, LowRamLoadControl.effectiveByteCeiling(1f))
        assertEquals(20 * 1024 * 1024, LowRamLoadControl.effectiveByteCeiling(2f))
        assertEquals(20 * 1024 * 1024, LowRamLoadControl.effectiveByteCeiling(4f))
    }

    @Test
    fun `slow playback cannot shrink the ceiling below the floor either`() {
        assertEquals(20 * 1024 * 1024, LowRamLoadControl.effectiveByteCeiling(0.5f))
    }
}
