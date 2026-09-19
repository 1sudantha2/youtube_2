package com.ultra.youtube.app

import com.ultra.youtube.app.data.innertube.CommentParams
import com.ultra.youtube.app.data.innertube.InnerTubeClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Request-shape tests for the parts of [InnerTubeClient] that do not need a network.
 *
 * `createCommentParams` is the one place the app hand-builds an opaque protobuf blob, so it
 * is pinned to a byte-for-byte expectation computed independently of this code.
 */
class InnerTubeClientTest {

    @Test
    fun `createCommentParams is field 2 holding the video id`() {
        // Independently computed: base64url(0x12 0x0B "dQw4w9WgXcQ")
        assertEquals("EgtkUXc0dzlXZ1hjUQ", CommentParams.build("dQw4w9WgXcQ"))
    }

    @Test
    fun `createCommentParams scales the length prefix with the id`() {
        val decoded = java.util.Base64.getUrlDecoder().decode(CommentParams.build("abcdefghij"))
        assertEquals(0x12.toByte(), decoded[0])
        assertEquals(10.toByte(), decoded[1])
        assertEquals("abcdefghij", String(decoded.copyOfRange(2, decoded.size)))
    }

    @Test
    fun `createCommentParams is unpadded base64url`() {
        val params = CommentParams.build("aaaaaaaaaaaaa") // 13 bytes -> padding would appear
        assertTrue("no padding characters", '=' !in params)
        assertTrue("url-safe alphabet", params.matches(Regex("^[A-Za-z0-9_-]+$")))
    }

    @Test
    fun `client profiles declare the fields the endpoint validates`() {
        for (profile in listOf(InnerTubeClient.ANDROID, InnerTubeClient.WEB, InnerTubeClient.TVHTML5)) {
            assertTrue(profile.clientName.isNotBlank())
            assertTrue(profile.clientVersion.isNotBlank())
            assertTrue(profile.platform.isNotBlank())
            assertTrue(profile.userAgent.isNotBlank())
        }
        // The Android profile is what buys us pre-signed stream URLs.
        assertEquals("ANDROID", InnerTubeClient.ANDROID.clientName)
        assertEquals("MOBILE", InnerTubeClient.ANDROID.platform)
        assertEquals(34, InnerTubeClient.ANDROID.androidSdkVersion)
    }

    @Test
    fun `endpoint base is the public InnerTube host`() {
        assertEquals("https://www.youtube.com/youtubei/v1", InnerTubeClient.BASE_URL)
        assertTrue(InnerTubeClient.API_KEY.isNotBlank())
    }
}
