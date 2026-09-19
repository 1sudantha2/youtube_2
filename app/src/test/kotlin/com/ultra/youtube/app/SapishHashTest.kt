package com.ultra.youtube.app

import com.ultra.youtube.app.data.innertube.SapishHash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SAPISIDHASH is the one piece of the auth flow that is easy to get subtly wrong (epoch
 * seconds vs millis, hash input order, hex casing), so it is pinned to independently
 * computed SHA-1 vectors.
 */
class SapishHashTest {

    @Test
    fun `hash matches an independently computed sha-1`() {
        val expected = "c3777f2381d35acfd779199d5e5f4fd435bcac95"
        assertEquals(expected, SapishHash.hash("1700000000 SAPISID_SAMPLE_123 https://www.youtube.com"))
    }

    @Test
    fun `header uses epoch seconds and lowercase hex`() {
        val header = SapishHash.header(
            sapisid = "SAPISID_SAMPLE_123",
            timestampMs = 1_700_000_000_000L,
        )
        assertEquals(
            "SAPISIDHASH 1700000000_c3777f2381d35acfd779199d5e5f4fd435bcac95",
            header,
        )
    }

    @Test
    fun `second vector`() {
        val header = SapishHash.header(
            sapisid = "abcDEF_123456",
            timestampMs = 1_609_459_200_123L, // milliseconds are truncated to seconds
        )
        assertEquals("SAPISIDHASH 1609459200_ca2431a733527cf64f407389bf603456dcccefa4", header)
    }

    @Test
    fun `origin is part of the signed input`() {
        val a = SapishHash.header("S", 1_700_000_000_000L, "https://www.youtube.com")
        val b = SapishHash.header("S", 1_700_000_000_000L, "https://music.youtube.com")
        assertTrue("different origins must produce different headers", a != b)
    }

    @Test
    fun `blank sapisid yields no header`() {
        assertNull(SapishHash.header(null, 1_700_000_000_000L))
        assertNull(SapishHash.header("", 1_700_000_000_000L))
        assertNull(SapishHash.header("   ", 1_700_000_000_000L))
    }

    @Test
    fun `hash is 40 lowercase hex characters`() {
        val hash = SapishHash.hash("anything")
        assertEquals(40, hash.length)
        assertTrue(hash.matches(Regex("^[0-9a-f]{40}$")))
    }
}
