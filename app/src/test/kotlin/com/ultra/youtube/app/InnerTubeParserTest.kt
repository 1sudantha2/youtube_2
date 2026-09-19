package com.ultra.youtube.app

import com.ultra.youtube.app.data.innertube.InnerTubeParser
import com.ultra.youtube.app.data.innertube.JsonExt
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * InnerTube response parsing, driven by recorded-shaped fixtures.
 *
 * These are the paths that break in production whenever YouTube renames a renderer, so the
 * fixtures deliberately include the awkward cases: renderer wrapping (`richItemRenderer` →
 * `videoWithContextRenderer` → `videoRenderer`), `runs` vs `simpleText` text nodes, and a
 * continuation token buried several levels down.
 */
class InnerTubeParserTest {

    private fun parse(json: String): JsonObject =
        JsonExt.json.parseToJsonElement(json) as JsonObject

    // ------------------------------------------------------------------ feeds

    @Test
    fun `parses a bare videoRenderer`() {
        val response = parse(
            """
            {"contents":{"singleColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":
              {"sectionListRenderer":{"contents":[{"itemSectionRenderer":{"contents":[
                {"videoRenderer":{"videoId":"dQw4w9WgXcQ",
                  "title":{"runs":[{"text":"Never Gonna Give You Up"}]},
                  "ownerText":{"runs":[{"text":"Rick Astley","navigationEndpoint":
                    {"browseEndpoint":{"browseId":"UCuAXFkgsw1L7xaCfnd5JJOw"}}}]},
                  "lengthText":{"simpleText":"3:33"},
                  "viewCountText":{"simpleText":"1,500,000,000 views"},
                  "publishedTimeText":{"simpleText":"15 years ago"},
                  "thumbnail":{"thumbnails":[
                    {"url":"https://i.ytimg.com/vi/dQw4w9WgXcQ/default.jpg","height":90},
                    {"url":"https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg","height":360}]}}}}]}}]}}}]}}}}
            """.trimIndent(),
        )

        val result = InnerTubeParser.parseFeed(response)
        assertEquals(1, result.items.size)
        val video = result.items.first().let { it as com.ultra.youtube.app.domain.FeedItem.Video }.video
        assertEquals("dQw4w9WgXcQ", video.id)
        assertEquals("Never Gonna Give You Up", video.title)
        assertEquals("Rick Astley", video.channelName)
        assertEquals("UCuAXFkgsw1L7xaCfnd5JJOw", video.channelId)
        assertEquals(213, video.durationSeconds) // 3:33
        assertEquals("1,500,000,000 views", video.viewCountText)
        // Picks the largest thumbnail, not the first.
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg", video.thumbnailUrl)
        assertEquals("v:dQw4w9WgXcQ", result.items.first().key)
    }

    @Test
    fun `unwraps richItemRenderer and videoWithContextRenderer nesting`() {
        val response = parse(
            """
            {"contents":{"richGridRenderer":{"contents":[
              {"richItemRenderer":{"content":{"videoWithContextRenderer":{
                 "videoId":"aaa111","title":{"runs":[{"text":"Wrapped twice"}]},
                 "thumbnail":{"thumbnails":[{"url":"https://example.com/a.jpg"}]}}}}},
              {"richItemRenderer":{"content":{"videoRenderer":{
                 "videoId":"bbb222","title":{"simpleText":"Bare renderer"},
                 "thumbnail":{"thumbnails":[]}}}}}
            ]}}}
            """.trimIndent(),
        )

        val result = InnerTubeParser.parseFeed(response)
        assertEquals(listOf("aaa111", "bbb222"), result.items.map { (it as com.ultra.youtube.app.domain.FeedItem.Video).video.id })
        assertEquals("Wrapped twice", (result.items[0] as com.ultra.youtube.app.domain.FeedItem.Video).video.title)
        assertEquals("Bare renderer", (result.items[1] as com.ultra.youtube.app.domain.FeedItem.Video).video.title)
    }

    @Test
    fun `extracts the continuation token from a continuationItemRenderer`() {
        val response = parse(
            """
            {"contents":{"sectionListRenderer":{"contents":[
              {"itemSectionRenderer":{"contents":[
                {"videoRenderer":{"videoId":"x1","title":{"simpleText":"One"},"thumbnail":{"thumbnails":[]}}},
                {"continuationItemRenderer":{"button":{"buttonRenderer":{"command":
                  {"continuationCommand":{"token":"TOKEN_ABC123"}}}}}}]}}]}}}
            """.trimIndent(),
        )
        assertEquals("TOKEN_ABC123", InnerTubeParser.parseFeed(response).continuation)
    }

    @Test
    fun `ignores renderers without a videoId instead of crashing`() {
        val response = parse(
            """{"contents":{"sectionListRenderer":{"contents":[{"itemSectionRenderer":{"contents":[
                 {"videoRenderer":{"title":{"simpleText":"no id"}}},
                 {"unknownFutureRenderer":{"something":true}}]}}]}}}"""
                .trimIndent(),
        )
        assertTrue(InnerTubeParser.parseFeed(response).items.isEmpty())
    }

    @Test
    fun `empty response degrades to an empty feed`() {
        val result = InnerTubeParser.parseFeed(parse("{}"))
        assertTrue(result.items.isEmpty())
        assertTrue(result.shelves.isEmpty())
        assertNull(result.continuation)
    }

    // ------------------------------------------------------------------ watch

    @Test
    fun `parses formats including cipher and index ranges`() {
        val response = parse(
            """
            {"playerResponse":{
              "videoDetails":{"videoId":"dQw4w9WgXcQ","title":"Rick","author":"Rick Astley",
                "channelId":"UCuAXFkgsw1L7xaCfnd5JJOw","lengthSeconds":"213",
                "shortDescription":"hello","thumbnail":{"thumbnails":[{"url":"t.jpg","height":720}]}},
              "streamingData":{
                "adaptiveFormats":[
                  {"itag":137,"mimeType":"video/mp4; codecs=\"avc1.640028\"","bitrate":3000000,
                   "width":1920,"height":1080,"fps":30,"qualityLabel":"1080p",
                   "initRange":{"start":"0","end":"740"},"indexRange":{"start":"741","end":"1200"},
                   "url":"https://rr1.googlevideo.com/videoplayback?id=137"},
                  {"itag":140,"mimeType":"audio/mp4; codecs=\"mp4a.40.2\"","bitrate":128000,
                   "audioSampleRate":"44100","audioChannels":2,
                   "initRange":{"start":"0","end":"631"},"indexRange":{"start":"632","end":"999"},
                   "url":"https://rr2.googlevideo.com/videoplayback?id=140"},
                  {"itag":22,"mimeType":"video/mp4; codecs=\"avc1.64001F,mp4a.40.2\"","bitrate":900000,
                   "width":1280,"height":720,"qualityLabel":"720p",
                   "signatureCipher":"s=ABC%2BDEF%2BGHI&sp=sig&url=https%3A%2F%2Frr3.googlevideo.com%2Fvideoplayback%3Fid%3D22"}]}}}
            """.trimIndent(),
        )

        val detail = InnerTubeParser.parseVideoDetail(response, "dQw4w9WgXcQ")
        assertEquals("Rick", detail.video.title)
        assertEquals("Rick Astley", detail.video.channelName)
        assertEquals(213, detail.video.durationSeconds)
        assertEquals(3, detail.formats.size)

        val video1080 = detail.formats.first { it.itag == 137 }
        assertEquals(1080, video1080.height)
        assertEquals("video/mp4", video1080.mimeType)
        assertEquals("avc1.640028", video1080.codecs)
        assertEquals(0L, video1080.initRangeStart)
        assertEquals(740L, video1080.initRangeEnd)
        assertEquals(741L, video1080.indexRangeStart)
        assertEquals(1200L, video1080.indexRangeEnd)
        assertTrue(video1080.isVideo)

        val audio = detail.formats.first { it.itag == 140 }
        assertTrue(audio.isAudio)
        assertEquals(44100, audio.audioSampleRate)
        assertEquals("mp4a.40.2", audio.codecs)

        val ciphered = detail.formats.first { it.itag == 22 }
        assertNull("url moves out of the plain field when ciphered", ciphered.url?.contains("signature"))
        assertEquals("ABC+DEF+GHI", ciphered.signature)
        assertEquals("sig", ciphered.signatureParam)
        assertEquals("https://rr3.googlevideo.com/videoplayback?id=22", ciphered.url)
    }

    @Test
    fun `video without streamingData still yields metadata`() {
        val response = parse(
            """{"playerResponse":{"videoDetails":{"videoId":"x","title":"T","author":"A","lengthSeconds":"10"}}}"""
                .trimIndent(),
        )
        val detail = InnerTubeParser.parseVideoDetail(response, "x")
        assertEquals("T", detail.video.title)
        assertTrue(detail.formats.isEmpty())
        assertNull(detail.commentsContinuation)
    }

    // ------------------------------------------------------------------ comments

    @Test
    fun `parses commentEntityPayload comments and their continuation`() {
        val response = parse(
            """
            {"continuationContents":{"itemSectionContinuation":{
              "header":{"commentsHeaderRenderer":{"countText":{"runs":[{"text":"1,234 Comments"}]}}},
              "contents":[
                {"commentThreadRenderer":{"comment":{"commentEntityPayload":{
                  "key":"c1",
                  "properties":{"content":{"content":"First!"},"likeCountNotliked":"42"},
                  "author":{"displayName":"@someone","publishedTime":"2 days ago",
                    "avatarImage":{"sources":[{"url":"avatar.jpg"}]}}}}}},
                {"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"NEXT_PAGE"}}}}
              ]}}}
            """.trimIndent(),
        )

        val page = InnerTubeParser.parseComments(response)
        assertEquals("1,234 Comments", page.headerText)
        assertEquals(1, page.comments.size)
        assertEquals("@someone", page.comments[0].author)
        assertEquals("First!", page.comments[0].text)
        assertEquals("42", page.comments[0].likeCountText)
        assertEquals("NEXT_PAGE", page.continuation)
    }

    @Test
    fun `parses legacy commentRenderer comments`() {
        val response = parse(
            """
            {"continuationContents":{"itemSectionContinuation":{"contents":[
              {"commentThreadRenderer":{"comment":{"commentRenderer":{
                "commentId":"legacy1",
                "authorText":{"simpleText":"Legacy User"},
                "contentText":{"runs":[{"text":"Old shape, still parsed"}]},
                "publishedTimeText":{"runs":[{"text":"1 year ago"}]},
                "voteCount":{"simpleText":"7"},
                "authorIsChannelOwner":true}}}}]}}}
            """.trimIndent(),
        )
        val page = InnerTubeParser.parseComments(response)
        assertEquals(1, page.comments.size)
        assertEquals("Legacy User", page.comments[0].author)
        assertEquals("Old shape, still parsed", page.comments[0].text)
        assertTrue(page.comments[0].isAuthor)
    }

    @Test
    fun `comment response without a section yields an empty page`() {
        val page = InnerTubeParser.parseComments(parse("{}"))
        assertTrue(page.comments.isEmpty())
        assertNull(page.continuation)
    }

    // ------------------------------------------------------------------ helpers

    @Test
    fun `duration text parsing`() {
        assertEquals(0, InnerTubeParser.durationTextToSeconds(null))
        assertEquals(0, InnerTubeParser.durationTextToSeconds("Live"))
        assertEquals(9, InnerTubeParser.durationTextToSeconds("0:09"))
        assertEquals(213, InnerTubeParser.durationTextToSeconds("3:33"))
        assertEquals(3723, InnerTubeParser.durationTextToSeconds("1:02:03"))
    }

    @Test
    fun `text nodes resolve from all four shapes`() {
        assertEquals("simple", InnerTubeParser.textOf(parse("""{"simpleText":"simple"}""")))
        assertEquals("run1run2", InnerTubeParser.textOf(parse("""{"runs":[{"text":"run1"},{"text":"run2"}]}""")))
        assertEquals("content", InnerTubeParser.textOf(parse("""{"content":"content"}""")))
        assertNull(InnerTubeParser.textOf(null))
        assertNull(InnerTubeParser.textOf(parse("""{"runs":[]}""")))
    }

    @Test
    fun `formats without index ranges are not DASH-describable`() {
        val response = parse(
            """{"playerResponse":{"streamingData":{"formats":[
                {"itag":18,"mimeType":"video/mp4","bitrate":500000,"width":640,"height":360,
                 "url":"https://example.com/p.mp4"}]}}}"""
                .trimIndent(),
        )
        val format = InnerTubeParser.parseVideoDetail(response, "x").formats.single()
        assertEquals(com.ultra.youtube.app.domain.Format.UNSET, format.indexRangeStart)
        assertNotNull(format.url)
    }
}
