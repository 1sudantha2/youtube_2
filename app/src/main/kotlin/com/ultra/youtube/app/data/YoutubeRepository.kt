package com.ultra.youtube.app.data

import com.ultra.youtube.app.data.auth.CookieStore
import com.ultra.youtube.app.data.innertube.InnerTubeClient
import com.ultra.youtube.app.data.innertube.InnerTubeParser
import com.ultra.youtube.app.domain.ActionResult
import com.ultra.youtube.app.domain.CommentPage
import com.ultra.youtube.app.domain.FeedItem
import com.ultra.youtube.app.domain.VideoDetail
import com.ultra.youtube.app.domain.VideoItem
import kotlinx.serialization.json.JsonObject

/**
 * The single place that knows how to turn an InnerTube response into domain data.
 *
 * Everything is `suspend` and network-bound; ViewModels call it from `viewModelScope`.
 * Feeds use the `ANDROID` client profile (lightest payloads, pre-signed streams), while
 * the watch page falls back to `WEB` when the Android payload has no usable formats.
 */
class YoutubeRepository(
    private val client: InnerTubeClient,
    val cookieStore: CookieStore,
) {

    /** InnerTube browse ids for the built-in feeds. */
    object Feed {
        const val HOME = "FEwhat_to_watch"
        const val SUBSCRIPTIONS = "FEsubscriptions"
        const val LIBRARY = "FElibrary"
        const val HISTORY = "FEhistory"
        const val CHANNEL = "FEchannels"

        /** `params` that scope a browse to the Watch Later playlist. */
        const val WATCH_LATER_PARAMS = "8gSB8JecAgoFV0wAAA%3D%3D"

        /** Watch Later is addressed by playlist id, which varies per account. */
        fun watchLater(playlistId: String) = "VL$playlistId"
    }

    suspend fun homeFeed(continuation: String? = null): FeedResult =
        feed(client.browse(Feed.HOME, continuation = continuation))

    suspend fun subscriptions(continuation: String? = null): FeedResult =
        feed(client.browse(Feed.SUBSCRIPTIONS, continuation = continuation))

    suspend fun library(continuation: String? = null): FeedResult =
        feed(client.browse(Feed.LIBRARY, continuation = continuation))

    suspend fun history(continuation: String? = null): FeedResult =
        feed(client.browse(Feed.HISTORY, continuation = continuation))

    suspend fun watchLater(playlistId: String, continuation: String? = null): FeedResult =
        feed(client.browse(Feed.watchLater(playlistId), continuation = continuation))

    suspend fun channel(channelId: String, continuation: String? = null): FeedResult =
        feed(client.browse(channelId, continuation = continuation))

    suspend fun search(query: String, continuation: String? = null): FeedResult =
        feed(client.search(query, continuation = continuation))

    private suspend fun feed(response: JsonObject): FeedResult {
        val parsed = InnerTubeParser.parseFeed(response)
        return FeedResult(
            items = parsed.items,
            shelves = parsed.shelves,
            continuation = parsed.continuation,
        )
    }

    /**
     * Watch page. Tries the Android profile first; if the payload has no playable formats
     * (region/device restrictions) it retries with the Web profile.
     */
    suspend fun videoDetail(videoId: String): VideoDetail {
        val androidResponse = runCatching { client.next(videoId, client = InnerTubeClient.ANDROID) }.getOrNull()
        val android = androidResponse?.let { InnerTubeParser.parseVideoDetail(it, videoId) }
        if (android != null && android.formats.any { it.url != null }) return android

        val webResponse = client.next(videoId, client = InnerTubeClient.WEB)
        return InnerTubeParser.parseVideoDetail(webResponse, videoId)
    }

    suspend fun comments(videoId: String, continuation: String? = null): CommentPage {
        val detail = videoDetail(videoId)
        val token = continuation ?: detail.commentsContinuation
            ?: return CommentPage(emptyList(), "", null)
        return InnerTubeParser.parseComments(client.continuation(token))
    }

    // ------------------------------------------------------------ interactions

    suspend fun like(videoId: String, params: String?): ActionResult =
        action { client.like(videoId, params) }

    suspend fun dislike(videoId: String, params: String?): ActionResult =
        action { client.dislike(videoId, params) }

    suspend fun removeLike(videoId: String, params: String?): ActionResult =
        action { client.removeLike(videoId, params) }

    suspend fun subscribe(channelId: String, params: String?): ActionResult =
        action { client.subscribe(channelId, params) }

    suspend fun unsubscribe(channelId: String, params: String?): ActionResult =
        action { client.unsubscribe(channelId, params) }

    suspend fun postComment(videoId: String, text: String): ActionResult =
        action { client.createComment(videoId, text) }

    private suspend fun action(block: suspend () -> JsonObject): ActionResult = runCatching {
        val response = block()
        ActionResult(
            success = true,
            statusText = response["status"]?.let { InnerTubeParser.textOf(it) }.orEmpty(),
            toggleButtonTextId = null,
        )
    }.getOrElse {
        ActionResult(success = false, statusText = it.message.orEmpty(), toggleButtonTextId = null)
    }

    /** The account's Watch Later playlist id (`VL…`), scraped from the library browse. */
    suspend fun watchLaterPlaylistId(): String? {
        val response = runCatching { client.browse(Feed.LIBRARY) }.getOrNull() ?: return null
        return findPlaylistId(response, "Watch later")
    }

    private fun findPlaylistId(response: JsonObject, title: String): String? {
        val renderer = findRendererWithTitle(response, title) ?: return null
        return renderer["playlistId"]?.let { InnerTubeParser.textOf(it) }
            ?: renderer["navigationEndpoint"]
                ?.let { InnerTubeParser.textOf(it) }
                ?.takeIf { it.startsWith("VL") }
    }

    private fun findRendererWithTitle(node: kotlinx.serialization.json.JsonElement?, title: String, depth: Int = 0): JsonObject? {
        if (node == null || depth > 16) return null
        val obj = node as? JsonObject
        if (obj != null) {
            val shelfTitle = InnerTubeParser.textOf(obj["title"])
            if (shelfTitle != null && shelfTitle.equals(title, ignoreCase = true)) return obj
            for (value in obj.values) {
                findRendererWithTitle(value, title, depth + 1)?.let { return it }
            }
            return null
        }
        if (node is kotlinx.serialization.json.JsonArray) {
            for (value in node) {
                findRendererWithTitle(value, title, depth + 1)?.let { return it }
            }
        }
        return null
    }

    data class FeedResult(
        val items: List<FeedItem>,
        val shelves: List<FeedItem.Shelf>,
        val continuation: String?,
    ) {
        val videos: List<VideoItem> get() = items.mapNotNull { (it as? FeedItem.Video)?.video }
    }
}
