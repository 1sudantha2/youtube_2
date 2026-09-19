package io.github.sudantha.youtubelite

import io.github.sudantha.youtubelite.player.LowRamLoadControl
import org.junit.Assert.assertEquals
import org.junit.Test

class LowRamLoadControlTest {
    @Test
    fun `uses the specified aggressive buffer targets`() {
        val control = LowRamLoadControl.create()
        // Media3 1.5 stores durations in microseconds; assert the exact spec values.
        assertEquals(10_000L * 1_000, longField(control, "minBufferUs"))
        assertEquals(25_000L * 1_000, longField(control, "maxBufferUs"))
        assertEquals(1_500L * 1_000, longField(control, "bufferForPlaybackUs"))
        assertEquals(3_000L * 1_000, longField(control, "bufferForPlaybackAfterRebufferUs"))
        assertEquals(0L, longField(control, "backBufferDurationUs"))
        assertEquals(LowRamLoadControl.TARGET_BYTES, intField(control, "targetBufferBytesOverwrite"))
    }

    private fun longField(target: Any, name: String): Long {
        val field = target::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.getLong(target)
    }

    private fun intField(target: Any, name: String): Int {
        val field = target::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.getInt(target)
    }
}
