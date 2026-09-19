package com.ultra.youtube.app.data.innertube

import com.ultra.youtube.app.data.innertube.JsonExt.array
import com.ultra.youtube.app.data.innertube.JsonExt.bool
import com.ultra.youtube.app.data.innertube.JsonExt.int
import com.ultra.youtube.app.data.innertube.JsonExt.obj
import com.ultra.youtube.app.data.innertube.JsonExt.path
import com.ultra.youtube.app.data.innertube.JsonExt.string
import com.ultra.youtube.app.domain.CaptionTrack
import com.ultra.youtube.app.domain.Comment
import com.ultra.youtube.app.domain.CommentPage
import com.ultra.youtube.app.domain.FeedItem
import com.ultra.youtube.app.domain.Format
import com.ultra.youtube.app.domain.VideoDetail
import com.ultra.youtube.app.domain.VideoItem
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Maps InnerTube JSON onto [com.ultra.youtube.app.domain] models.
 *
 * InnerTube has no schema and renames renderers without notice, so every accessor here is
 * defensive and every list degrades to `emptyList()`. Pure JVM: fully unit-testable
 * against recorded fixtures.
 */
object InnerTubeParser {

    // ------------------------------------------------------------ feeds

    /**
     * Parses a `browse` / `search` response (or a continuation of one) into feed items.
     * Handles the renderer wrappings YouTube nests arbitrarily deep:
     * `richGridRenderer -> richItemRenderer -> videoWithContextRenderer -> videoRenderer`.
     */
    fun parseFeed(response: JsonObject): FeedParseResult {
        val items = ArrayList<FeedItem>()
        val shelves = ArrayList<FeedItem.Shelf>()
        collectRenderers(response, items, shelves)
        return FeedParseResult(
            items = items,
            shelves = shelves,
            continuation = extractContinuation(response),
        )
    }

    data class FeedParseResult(
        val items: List<FeedItem>,
        val shelves: List<FeedItem.Shelf>,
        val continuation: String?,
    )

    private val VIDEO_RENDERERS = listOf("videoRenderer", "videoWithContextRenderer", "compactVideoRenderer", "gridVideoRenderer", "playlistVideoRenderer", "reelItemRenderer", "shortsLockupViewModel")
    private val WRAPPER_RENDERERS = listOf("richItemRenderer", "lockupViewModel", "itemSectionRenderer", "sectionListRenderer", "shelfRenderer", "richSectionRenderer", "musicShelfRenderer", "videoWithContextRenderer")

    private fun collectRenderers(
        node: JsonElement?,
        out: MutableList<FeedItem>,
        shelves: MutableList<FeedItem.Shelf>,
        depth: Int = 0,
    ) {
        if (node == null || depth > 14) return
        val obj = node.obj() ?: run {
            if (node is kotlinx.serialization.json.JsonArray) {
                node.forEach { collectRenderers(it, out, shelves, depth + 1) }
            }
            return
        }

        for (renderer in VIDEO_RENDERERS) {
            val child = obj[renderer] ?: continue
            parseVideoRenderer(child)?.let { out += FeedItem.Video(it) }
            return
        }
        obj["channelRenderer"]?.let {
            parseChannelRenderer(it)?.let { channel -> out += channel }
            return
        }
        obj["shelfRenderer"]?.let { shelf ->
            val title = shelf.path("title", "simpleText")?.string()
                ?: shelf.path("title", "runs", 0, "text")?.string()
            val videos = shelf.path("content", "horizontalListRenderer", "items")
                .array()
                .mapNotNull { parseVideoRenderer(unwrap(it)) }
            if (title != null && videos.isNotEmpty()) {
                shelves += FeedItem.Shelf(
                    id = "shelf-${shelves.size}-$title",
                    title = title,
                    videos = videos,
                )
            }
            return
        }
        obj["continuationItemRenderer"]?.let { return }

        // Recurse into any known wrapper.
        for (wrapper in WRAPPER_RENDERERS) {
            val child = obj[wrapper] ?: continue
            if (wrapper == "videoWithContextRenderer") {
                parseVideoRenderer(child)?.let { out += FeedItem.Video(it) }
                return
            }
            collectRenderers(child, out, shelves, depth + 1)
            return
        }
        // Unknown shape: walk every object/array value one level.
        obj.values.forEach { collectRenderers(it, out, shelves, depth + 1) }
    }

    /** Strips one layer of renderer wrapping, e.g. `{"richItemRenderer":{"content":{…}}}`. */
    private fun unwrap(node: JsonElement): JsonElement {
        val obj = node.obj() ?: return node
        for (wrapper in WRAPPER_RENDERERS) {
            val inner = obj[wrapper] ?: continue
            return if (wrapper == "richItemRenderer") inner.path("content") ?: inner else inner
        }
        return node
    }

    fun parseVideoRenderer(node: JsonElement?): VideoItem? {
        val renderer = node?.obj() ?: return null
        val id = renderer.path("videoId")?.string() ?: return null
        val title = textOf(renderer["title"]) ?: textOf(renderer.path("headline")) ?: return null

        val thumbnails = renderer.path("thumbnail", "thumbnails").array()
        val thumb = thumbnails.maxByOrNull { it["height"].int() } ?: thumbnails.lastOrNull()

        return VideoItem(
            id = id,
            title = title,
            channelName = textOf(renderer["ownerText"])
                ?: textOf(renderer["longBylineText"])
                ?: textOf(renderer["shortBylineText"])
                ?: "",
            channelId = renderer.path("ownerText", "runs", 0, "navigationEndpoint", "browseEndpoint", "browseId")?.string()
                ?: renderer.path("longBylineText", "runs", 0, "navigationEndpoint", "browseEndpoint", "browseId")?.string()
                ?: "",
            durationSeconds = durationTextToSeconds(textOf(renderer["lengthText"])),
            viewCountText = textOf(renderer["viewCountText"]) ?: textOf(renderer["shortViewCountText"]) ?: "",
            publishedTimeText = textOf(renderer["publishedTimeText"]) ?: "",
            thumbnailUrl = thumb?.path("url")?.string().orEmpty(),
            isLive = renderer.path("badges").array().any {
                (it.path("metadataBadgeRenderer", "style")?.string() ?: "").contains("LIVE", true)
            } || textOf(renderer["lengthText"]) == null && renderer.path("upcomingEventData").obj() != null,
            isUpcoming = renderer.path("upcomingEventData").obj() != null,
            isShort = renderer["reelPlayerHeaderRenderer"] != null ||
                (renderer.path("navigationEndpoint", "reelWatchEndpoint") != null),
        )
    }

    private fun parseChannelRenderer(node: JsonElement): FeedItem.Channel? {
        val renderer = node.obj() ?: return null
        val id = renderer.path("channelId")?.string() ?: return null
        return FeedItem.Channel(
            channelId = id,
            title = textOf(renderer["title"]) ?: "",
            avatarUrl = renderer.path("thumbnail", "thumbnails").array()
                .lastOrNull()?.path("url")?.string().orEmpty(),
            subscriberCountText = textOf(renderer["videoCountText"]) ?: "",
        )
    }

    // ------------------------------------------------------------ watch / player

    /**
     * Parses a `next` response into everything the player and the watch screen need.
     *
     * @param videoId the requested id, used when the payload omits it
     */
    fun parseVideoDetail(response: JsonObject, videoId: String): VideoDetail {
        val player = response.path("playerResponse").obj() ?: response
        val streamingData = player["streamingData"]
        val videoDetails = player["videoDetails"]
        val microformat = player.path("microformat", "playerMicroformatRenderer")

        val title = videoDetails.path("title")?.string()
            ?: microformat.path("title", "simpleText")?.string()
            ?: response.path("contents", "singleColumnWatchNextResults", "results", "results", "contents")
                .array()
                .firstNotNullOfOrNull { textOf(it.path("videoPrimaryInfoRenderer", "title")) }
            ?: "Unknown title"

        val channelId = videoDetails.path("channelId")?.string().orEmpty()
        val channelName = videoDetails.path("author")?.string()
            ?: response.path("contents", "singleColumnWatchNextResults", "results", "results", "contents")
                .array()
                .firstNotNullOfOrNull {
                    textOf(it.path("videoSecondaryInfoRenderer", "owner", "videoOwnerRenderer", "title"))
                }
            ?: ""

        val thumbnails = videoDetails.path("thumbnail", "thumbnails").array()
        val thumbnailUrl = (thumbnails.maxByOrNull { it["height"].int() } ?: thumbnails.lastOrNull())
            ?.path("url")?.string()
            .orEmpty()

        val video = VideoItem(
            id = videoDetails.path("videoId")?.string() ?: videoId,
            title = title,
            channelName = channelName,
            channelId = channelId,
            durationSeconds = videoDetails.path("lengthSeconds")?.string()?.toIntOrNull() ?: 0,
            viewCountText = videoDetails.path("viewCount")?.string().orEmpty(),
            publishedTimeText = microformat.path("publishDate")?.string().orEmpty(),
            thumbnailUrl = thumbnailUrl,
            isLive = videoDetails.path("isLiveContent").bool() && videoDetails.path("isLive").bool(),
        )

        val formats = buildList {
            streamingData.path("adaptiveFormats").array().forEach { parseFormat(it)?.let(this::add) }
            streamingData.path("formats").array().forEach { parseFormat(it)?.let(this::add) }
        }

        val captions = player.path("captions", "playerCaptionsTracklistRenderer", "captionTracks")
            .array()
            .mapNotNull { track ->
                val url = track.path("baseUrl")?.string() ?: return@mapNotNull null
                CaptionTrack(
                    baseUrl = url,
                    languageCode = track.path("languageCode")?.string().orEmpty(),
                    name = textOf(track["name"]) ?: track.path("languageCode")?.string().orEmpty(),
                    isTranslatable = track.path("isTranslatable").bool(),
                )
            }

        val panels = response.path("engagementPanels").array()
        val secondary = response.path("contents", "singleColumnWatchNextResults", "results", "results", "contents").array()
            .ifEmpty { response.path("contents", "twoColumnWatchNextResults", "results", "results", "contents").array() }

        val videoPrimary = secondary.firstNotNullOfOrNull { it["videoPrimaryInfoRenderer"] }
        val videoSecondary = secondary.firstNotNullOfOrNull { it["videoSecondaryInfoRenderer"] }

        val related = response.path("contents", "twoColumnWatchNextResults", "secondaryResults", "secondaryResults", "results")
            .array()
            .mapNotNull { parseVideoRenderer(unwrap(it)) }

        return VideoDetail(
            video = video,
            description = videoDetails.path("shortDescription")?.string().orEmpty(),
            likeCountText = textOf(videoPrimary.path("videoActions", "menuRenderer", "topLevelButtons"))
                ?: textOf(videoPrimary.path("videoActions", "menuRenderer", "topLevelButtons", 0, "segmentedLikeDislikeButtonRenderer", "likeButton", "toggleButtonRenderer", "defaultText"))
                ?: "",
            viewCountText = textOf(videoPrimary["viewCount"]) ?: video.viewCountText,
            formats = formats,
            captions = captions,
            related = related,
            likeParams = findLikeParams(secondary, panels, "LIKE"),
            dislikeParams = findLikeParams(secondary, panels, "DISLIKE"),
            subscribeParams = videoSecondary
                .path("owner", "videoOwnerRenderer", "subscriptionButton", "subscribeButtonRenderer", "serviceEndpoints", 0, "subscribeEndpoint", "params")
                ?.string(),
            commentsContinuation = findCommentsContinuation(secondary, panels),
            playerJsUrl = null,
        )
    }

    fun parseFormat(node: JsonElement?): Format? {
        val f = node?.obj() ?: return null
        val mimeType = f.path("mimeType")?.string() ?: return null
        val itag = f.path("itag")?.string()?.toIntOrNull() ?: return null

        // `signatureCipher` (WEB client) vs. a ready-to-use `url` (ANDROID client).
        var url: String? = f.path("url")?.string()
        var signature: String? = null
        var signatureParam = "signature"
        f.path("signatureCipher")?.string()?.let { cipher ->
            val params = cipher.split('&').associate {
                val eq = it.indexOf('=')
                if (eq <= 0) "" to "" else it.substring(0, eq) to java.net.URLDecoder.decode(it.substring(eq + 1), "UTF-8")
            }
            url = params["url"]
            signature = params["s"]
            signatureParam = params["sp"] ?: "signature"
        }

        return Format(
            itag = itag,
            url = url,
            mimeType = mimeType.substringBefore(';').trim(),
            codecs = com.ultra.youtube.app.player.DashManifestBuilder.parseCodecs(mimeType),
            bitrate = f.path("bitrate")?.string()?.toIntOrNull() ?: 0,
            width = f.path("width")?.string()?.toIntOrNull() ?: 0,
            height = f.path("height")?.string()?.toIntOrNull() ?: 0,
            fps = f.path("fps")?.string()?.toIntOrNull() ?: 0,
            contentLength = f.path("contentLength")?.string()?.toLongOrNull() ?: Format.UNSET,
            approxDurationMs = f.path("approxDurationMs")?.string()?.toLongOrNull() ?: 0L,
            audioSampleRate = f.path("audioSampleRate")?.string()?.toIntOrNull() ?: 0,
            audioChannels = f.path("audioChannels")?.string()?.toIntOrNull() ?: 0,
            qualityLabel = f.path("qualityLabel")?.string().orEmpty(),
            signature = signature,
            signatureParam = signatureParam,
            initRangeStart = f.path("initRange", "start")?.string()?.toLongOrNull() ?: Format.UNSET,
            initRangeEnd = f.path("initRange", "end")?.string()?.toLongOrNull() ?: Format.UNSET,
            indexRangeStart = f.path("indexRange", "start")?.string()?.toLongOrNull() ?: Format.UNSET,
            indexRangeEnd = f.path("indexRange", "end")?.string()?.toLongOrNull() ?: Format.UNSET,
        )
    }

    // ------------------------------------------------------------ comments

    fun parseComments(response: JsonObject): CommentPage {
        val root = response.path("continuationContents", "itemSectionContinuation")
            ?: response.path("onResponseReceivedEndpoints").array()
                .firstNotNullOfOrNull {
                    it.path("reloadContinuationItemsCommand", "continuationItems")
                        ?: it.path("appendContinuationItemsAction", "continuationItems")
                }
                ?: return CommentPage(emptyList(), "", null)

        val header = response.path("continuationContents", "itemSectionContinuation", "header", "commentsHeaderRenderer")
        val headerText = textOf(header.path("countText")) ?: textOf(header.path("titleText")) ?: ""

        val comments = root.array().mapNotNull { item ->
            val thread = item.path("commentThreadRenderer", "comment", "commentEntityPayload").obj()
                ?: item.path("commentViewModel", "commentViewModel").obj()
            if (thread != null) return@mapNotNull parseCommentEntityPayload(thread)
            val legacy = item.path("commentThreadRenderer", "comment", "commentRenderer").obj()
                ?: item["commentRenderer"].obj()
                ?: return@mapNotNull null
            parseCommentRenderer(legacy)
        }

        return CommentPage(
            comments = comments,
            headerText = headerText,
            continuation = extractContinuation(root),
        )
    }

    private fun parseCommentEntityPayload(payload: JsonObject): Comment {
        val props = payload["properties"]
        val author = payload["author"]
        return Comment(
            id = payload.path("key")?.string().orEmpty(),
            author = author.path("displayName")?.string().orEmpty(),
            authorAvatarUrl = author.path("avatarImage", "sources", 0, "url")?.string().orEmpty(),
            text = props.path("content", "content")?.string().orEmpty(),
            timeText = author.path("publishedTime")?.string().orEmpty(),
            likeCountText = props.path("likeCountNotliked")?.string()
                ?: props.path("likeCountA11y")?.string().orEmpty(),
            replyCountText = props.path("replyCount")?.string().orEmpty(),
            isAuthor = author.path("isVerified").bool(),
        )
    }

    private fun parseCommentRenderer(renderer: JsonObject): Comment = Comment(
        id = renderer.path("commentId")?.string().orEmpty(),
        author = textOf(renderer["authorText"]).orEmpty(),
        authorAvatarUrl = renderer.path("authorThumbnail", "thumbnails").array()
            .lastOrNull()?.path("url")?.string().orEmpty(),
        text = textOf(renderer["contentText"]).orEmpty(),
        timeText = textOf(renderer["publishedTimeText"]).orEmpty(),
        likeCountText = textOf(renderer["voteCount"]).orEmpty(),
        replyCountText = renderer.path("replyCount")?.string()?.let { "$it replies" }.orEmpty(),
        isAuthor = renderer.path("authorIsChannelOwner").bool(),
    )

    // ------------------------------------------------------------ helpers

    /** Reads a YouTube "text" node in any of its four shapes. */
    fun textOf(node: JsonElement?): String? {
        val obj = node ?: return null
        obj.path("simpleText")?.string()?.let { return it }
        val runs = obj.path("runs").array()
        if (runs.isNotEmpty()) {
            return runs.joinToString("") { it.path("text")?.string().orEmpty() }.ifBlank { null }
        }
        obj.path("content")?.string()?.let { return it }
        return (obj as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
    }

    /** `12:34` / `1:02:03` / `1 day ago` -> seconds (0 when unparseable). */
    fun durationTextToSeconds(text: String?): Int {
        if (text.isNullOrBlank()) return 0
        if (text.contains(':').not()) return 0
        return text.split(':').mapNotNull { it.trim().toIntOrNull() }
            .fold(0) { acc, unit -> acc * 60 + unit }
    }

    /** First `continuationCommand` token found while walking the response. */
    fun extractContinuation(node: JsonElement?, depth: Int = 0): String? {
        if (node == null || depth > 18) return null
        node.path("continuationEndpoint", "continuationCommand", "token")?.string()?.let { return it }
        node.path("continuationCommand", "token")?.string()?.let { return it }
        node.path("button", "buttonRenderer", "command", "continuationCommand", "token")?.string()?.let { return it }
        val obj = node.obj()
        if (obj != null) {
            for (value in obj.values) {
                extractContinuation(value, depth + 1)?.let { return it }
            }
            return null
        }
        if (node is kotlinx.serialization.json.JsonArray) {
            for (value in node) {
                extractContinuation(value, depth + 1)?.let { return it }
            }
        }
        return null
    }

    /**
     * Likes/dislikes on InnerTube need an opaque `params` blob. It is published inside the
     * watch response's like button; we search the two places it can live.
     */
    private fun findLikeParams(
        secondary: List<JsonElement>,
        panels: List<JsonElement>,
        kind: String,
    ): String? {
        val menu = secondary.firstNotNullOfOrNull { it.path("videoPrimaryInfoRenderer", "videoActions", "menuRenderer") }
        val buttons = menu.path("topLevelButtons").array()
        for (button in buttons) {
            val segmented = button.path("segmentedLikeDislikeButtonRenderer")
            val candidates = if (segmented != null) {
                listOf(
                    segmented.path("likeButton", "toggleButtonRenderer"),
                    segmented.path("dislikeButton", "toggleButtonRenderer"),
                )
            } else {
                listOf(button.path("toggleButtonRenderer"), button.path("segmentedLikeDislikeButtonViewModel"))
            }
            for (candidate in candidates) {
                val endpoints = candidate.path("defaultServiceEndpoint").obj()?.let { listOf(it) }
                    ?: candidate.path("toggledServiceEndpoint").obj()?.let { listOf(it) }
                    ?: emptyList()
                for (endpoint in endpoints) {
                    val likeEndpoint = endpoint.path("likeEndpoint").obj() ?: continue
                    if (likeEndpoint.path("status")?.string() == kind || endpoint.path("likeEndpoint", "status")?.string() == kind) {
                        likeEndpoint.path("params")?.string()?.let { return it }
                    }
                    likeEndpoint.path("params")?.string()?.let { return it }
                }
            }
        }
        // Fallback: any likeEndpoint params anywhere in the response.
        return findFirstKey(menu, "likeEndpoint")?.path("params")?.string()
            ?: panels.firstNotNullOfOrNull { findFirstKey(it, "likeEndpoint")?.path("params")?.string() }
    }

    /** The comments section is opened by a continuation token embedded in the watch response. */
    private fun findCommentsContinuation(
        secondary: List<JsonElement>,
        panels: List<JsonElement>,
    ): String? {
        for (item in secondary) {
            val section = item.path("itemSectionRenderer") ?: continue
            val target = section.path("sectionIdentifier")?.string()
            val node = section.path("contents", 0, "continuationItemRenderer")
            if (target == "comment-item-section" || node != null) {
                extractContinuation(node)?.let { return it }
            }
        }
        for (panel in panels) {
            val identifier = panel.path("panelIdentifier")?.string().orEmpty()
            if ("comment" in identifier) {
                extractContinuation(panel.path("continuationEndpoint"))?.let { return it }
            }
        }
        return null
    }

    /** Depth-first search for the first value stored under [key]. */
    private fun findFirstKey(node: JsonElement?, key: String, depth: Int = 0): JsonElement? {
        if (node == null || depth > 20) return null
        val obj = node.obj()
        if (obj != null) {
            obj[key]?.let { return it }
            for (value in obj.values) {
                findFirstKey(value, key, depth + 1)?.let { return it }
            }
            return null
        }
        if (node is kotlinx.serialization.json.JsonArray) {
            for (value in node) {
                findFirstKey(value, key, depth + 1)?.let { return it }
            }
        }
        return null
    }
}
