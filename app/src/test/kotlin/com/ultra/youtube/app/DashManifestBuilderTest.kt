package com.ultra.youtube.app

import com.ultra.youtube.app.domain.Format
import com.ultra.youtube.app.player.DashManifestBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The synthesised MPD is what makes DASH merging possible, so it is validated as real XML
 * here — a malformed manifest would only surface as a playback failure on a device.
 */
class DashManifestBuilderTest {

    private fun format(
        itag: Int,
        mime: String,
        height: Int = 0,
        bitrate: Int = 1_000_000,
        withRanges: Boolean = true,
        url: String = "https://rr.googlevideo.com/videoplayback?id=$itag&x=1",
    ) = Format(
        itag = itag,
        url = url,
        mimeType = mime.substringBefore(';'),
        codecs = DashManifestBuilder.parseCodecs(mime),
        bitrate = bitrate,
        width = if (height > 0) height * 16 / 9 else 0,
        height = height,
        fps = 30,
        contentLength = 10_000_000L,
        approxDurationMs = 60_000L,
        audioSampleRate = if (height == 0) 44_100 else 0,
        audioChannels = if (height == 0) 2 else 0,
        qualityLabel = if (height > 0) "${height}p" else "128kbps",
        signature = null,
        signatureParam = "signature",
        initRangeStart = if (withRanges) 0L else Format.UNSET,
        initRangeEnd = if (withRanges) 740L else Format.UNSET,
        indexRangeStart = if (withRanges) 741L else Format.UNSET,
        indexRangeEnd = if (withRanges) 1_200L else Format.UNSET,
    )

    private val typical = listOf(
        format(137, "video/mp4; codecs=\"avc1.640028\"", height = 1080, bitrate = 4_000_000),
        format(136, "video/mp4; codecs=\"avc1.4d401f\"", height = 720, bitrate = 2_000_000),
        format(140, "audio/mp4; codecs=\"mp4a.40.2\"", bitrate = 128_000),
    )

    @Test
    fun `builds a video and an audio manifest`() {
        val manifests = DashManifestBuilder.build(typical, durationMs = 213_000L)
        assertNotNull(manifests)
        assertEquals(2, manifests!!.videoFormats.size)
        assertEquals(1, manifests.audioFormats.size)
        assertTrue(manifests.video.startsWith("<?xml"))
        assertTrue(manifests.audio.contains("<AdaptationSet"))
    }

    @Test
    fun `the manifests are well-formed XML`() {
        val manifests = DashManifestBuilder.build(typical, durationMs = 213_000L)!!
        val video = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(manifests.video.byteInputStream())
        val audio = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(manifests.audio.byteInputStream())

        assertEquals("MPD", video.documentElement.tagName)
        assertEquals("MPD", audio.documentElement.tagName)
        assertEquals("PT213.000S", video.documentElement.getAttribute("mediaPresentationDuration"))
        assertEquals(
            2,
            video.getElementsByTagName("Representation").length,
            "one Representation per resolution",
        )
        assertEquals(1, audio.getElementsByTagName("Representation").length)
    }

    @Test
    fun `video renditions are ordered highest first`() {
        val manifests = DashManifestBuilder.build(typical, durationMs = 60_000L)!!
        assertEquals(listOf(1080, 720), manifests.videoFormats.map { it.height })
    }

    @Test
    fun `escapes ampersands in the BaseURL`() {
        val manifests = DashManifestBuilder.build(typical, durationMs = 60_000L)!!
        // The fixture URL contains `&x=1`; a raw `&` would break the XML.
        assertFalse(manifests.video.contains("&x=1"))
        assertTrue(manifests.video.contains("&amp;x=1"))
    }

    @Test
    fun `carries the SegmentBase and Initialization ranges`() {
        val manifests = DashManifestBuilder.build(typical, durationMs = 60_000L)!!
        assertTrue(manifests.video.contains("indexRange=\"741-1200\""))
        assertTrue(manifests.video.contains("<Initialization range=\"0-740\""))
    }

    @Test
    fun `returns null when only progressive streams exist`() {
        val progressive = listOf(format(18, "video/mp4", height = 360, withRanges = false))
        assertNull(DashManifestBuilder.build(progressive, durationMs = 60_000L))
    }

    @Test
    fun `returns null when there is no audio stream`() {
        val videoOnly = listOf(format(137, "video/mp4", height = 1080))
        assertNull(DashManifestBuilder.build(videoOnly, durationMs = 60_000L))
    }

    @Test
    fun `returns null for an empty format list`() {
        assertNull(DashManifestBuilder.build(emptyList(), durationMs = 60_000L))
    }

    @Test
    fun `collapses duplicate resolutions keeping the higher bitrate`() {
        val duplicates = listOf(
            format(137, "video/mp4", height = 1080, bitrate = 4_000_000),
            format(248, "video/webm", height = 1080, bitrate = 2_500_000),
            format(140, "audio/mp4"),
        )
        val manifests = DashManifestBuilder.build(duplicates, durationMs = 60_000L)!!
        assertEquals(1, manifests.videoFormats.size)
        assertEquals(137, manifests.videoFormats[0].itag)
    }

    @Test
    fun `codec extraction falls back to a safe default`() {
        assertEquals(
            "avc1.640028",
            DashManifestBuilder.parseCodecs("video/mp4; codecs=\"avc1.640028\""),
        )
        assertEquals(
            "avc1.64001F,mp4a.40.2",
            DashManifestBuilder.parseCodecs("video/mp4; codecs=\"avc1.64001F,mp4a.40.2\""),
        )
        assertEquals("", DashManifestBuilder.parseCodecs("video/mp4"))
        assertEquals(
            "avc1.4D401E",
            DashManifestBuilder.codecsOf(format(999, "video/mp4", height = 480)),
        )
        assertEquals(
            "mp4a.40.2",
            DashManifestBuilder.codecsOf(format(998, "audio/mp4")),
        )
    }

    @Test
    fun `duration floor keeps the MPD valid for very short videos`() {
        val manifests = DashManifestBuilder.build(typical, durationMs = 0L)!!
        assertTrue(manifests.video.contains("mediaPresentationDuration=\"PT1.000S\""))
    }
}
