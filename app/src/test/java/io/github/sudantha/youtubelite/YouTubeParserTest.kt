package io.github.sudantha.youtubelite

import io.github.sudantha.youtubelite.data.YouTubeParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeParserTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun parse(text: String): JsonElement = json.parseToJsonElement(text)

    @Test
    fun `parses classic videoRenderer feed with continuation`() {
        val root = parse(
            """
            {
              "contents": {
                "twoColumnBrowseResultsRenderer": {
                  "tabs": [ { "content": { "sectionListRenderer": { "contents": [
                    { "itemSectionRenderer": { "contents": [
                      { "videoRenderer": {
                          "videoId": "dQw4w9WgXcQ",
                          "title": { "runs": [ { "text": "Rick" }, { "text": " Astley" } ] },
                          "ownerText": { "runs": [ { "text": "Channel", "browseEndpoint": { "browseId": "UCabcdef123" } } ] },
                          "lengthText": { "simpleText": "3:33" },
                          "shortViewCountText": { "simpleText": "1B views" },
                          "thumbnail": { "thumbnails": [
                            { "url": "http://i.ytimg.com/vi/dQw4w9WgXcQ/hq720.jpg", "width": 1280, "height": 720 },
                            { "url": "http://i.ytimg.com/vi/dQw4w9WgXcQ/mqdefault.jpg", "width": 320, "height": 180 }
                          ] }
                        } }
                    ] } }
                  ], "continuationItemRenderer": { "continuationEndpoint": { "continuationCommand": { "token": "TOKEN1" } } }
                  } } } } ]
                }
              }
            }
            """.trimIndent()
        )
        val page = YouTubeParser.videos(root)
        assertEquals(1, page.videos.size)
        val video = page.videos[0]
        assertEquals("dQw4w9WgXcQ", video.id)
        assertEquals("Rick Astley", video.title)
        assertEquals("Channel", video.channel)
        assertEquals("UCabcdef123", video.channelId)
        assertEquals("3:33", video.duration)
        assertEquals("1B views", video.views)
        // 480p target picks the thumbnail closest to 480 width (320 beats 1280).
        assertEquals("http://i.ytimg.com/vi/dQw4w9WgXcQ/mqdefault.jpg", video.thumbnail)
        assertEquals("TOKEN1", page.continuation)
    }

    @Test
    fun `parses modern lockupViewModel feed`() {
        val root = parse(
            """
            {
              "contents": {
                "twoColumnBrowseResultsRenderer": {
                  "tabs": [ { "content": { "richGridRenderer": { "contents": [
                    { "richItemRenderer": {
                        "contentId": "dQw4w9WgXcQ",
                        "content": { "lockupViewModel": {
                            "textViewModel": { "title": { "content": "Modern title" } },
                            "metadata": { "lockupMetadataViewModel": { "metadataRows": [
                              { "metadataParts": [ { "text": { "content": "Channel" } }, { "text": { "content": "1B views" } } ] }
                            ] } },
                            "image": { "thumbnailViewModel": { "thumbnail": { "sources": [
                              { "url": "//i.ytimg.com/vi/dQw4w9WgXcQ/mqdefault.jpg", "width": 320, "height": 180 }
                            ] } } }
                        } } }
                  ] } } } ]
                }
              }
            }
            """.trimIndent()
        )
        val page = YouTubeParser.videos(root)
        assertEquals(1, page.videos.size)
        assertEquals("Modern title", page.videos[0].title)
        assertEquals("Channel · 1B views", page.videos[0].channel)
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/mqdefault.jpg", page.videos[0].thumbnail)
        assertNull(page.continuation)
    }

    @Test
    fun `parses commentRenderer and commentEntityPayload together`() {
        val root = parse(
            """
            {
              "continuationContents": { "engagementPanelSectionListRenderer": {
                "sectionListRenderer": { "contents": [
                  { "itemSectionRenderer": { "contents": [
                    { "commentRenderer": {
                        "commentId": "c1",
                        "authorText": { "simpleText": "alice" },
                        "contentText": { "runs": [ { "text": "first " }, { "text": "comment" } ] },
                        "voteCount": { "simpleText": "42" }
                      } }
                  ] } },
                  { "commentEntityPayload": {
                      "commentId": "c2",
                      "author": { "displayName": "bob" },
                      "properties": { "commentId": "c2", "content": { "simpleText": "second" } },
                      "toolbar": { "likeCountNotliked": { "simpleText": "7" } }
                    } }
                ] }
              } }
            }
            """.trimIndent()
        )
        val page = YouTubeParser.comments(root)
        assertEquals(2, page.comments.size)
        assertEquals(listOf("alice", "bob"), page.comments.map { it.author })
        assertEquals("first comment", page.comments[0].text)
        assertEquals("42", page.comments[0].likes)
        assertEquals("second", page.comments[1].text)
        assertEquals("7", page.comments[1].likes)
    }

    @Test
    fun `continuation search is recursive`() {
        val root = parse(
            """{ "a": { "b": [ { "c": [ { "continuationItemRenderer": { "continuationEndpoint": { "continuationCommand": { "token": "T" } } } } ] } ] } }"""
        )
        assertEquals("T", YouTubeParser.continuation(root))
    }

    @Test
    fun `invalid video ids are rejected`() {
        assertFalse(YouTubeParser.validVideoId("dQw4w9WgXc"))
        assertFalse(YouTubeParser.validVideoId("dQw4w9WgXcQ!"))
        assertTrue(YouTubeParser.validVideoId("dQw4w9WgXcQ"))
    }
}
