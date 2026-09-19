package app.you.tube.core.network

import app.you.tube.core.model.ChannelItem
import app.you.tube.core.model.CommentItem
import app.you.tube.core.model.CommentSortOption
import app.you.tube.core.model.CommentsPage
import app.you.tube.core.model.VideoItem
import app.you.tube.core.model.WatchNextInfo
import app.you.tube.core.util.objects
import app.you.tube.core.util.pathArr
import app.you.tube.core.util.pathBool
import app.you.tube.core.util.pathObj
import app.you.tube.core.util.pathStr
import kotlinx.collections.immutable.toImmutableList
import kotlinx.serialization.json.JsonObject

/**
 * Defensive InnerTube response parser.
 *
 * Every accessor is null-safe and every renderer shape is tried in order, so
 * YouTube-side schema drift produces graceful degradation (missing fields)
 * rather than crashes. Field paths mirror the verified Feb-2026 WEB client
 * responses, including the modern comments architecture
 * (`commentViewModel` + `frameworkUpdates.entityBatchUpdate.mutations`).
 */
object InnerTubeParser {

    // ------------------------------------------------------------------ feed

    /**
     * Unified feed parser for FEwhat_to_watch / FEsubscriptions / FEhistory /
     * playlists (VLLM, VLWL, ...). Handles every item renderer family:
     * richGrid (home/new playlists), gridRenderer (subscriptions),
     * playlistVideoListRenderer (playlists), itemSection+videoRenderer (history)
     * plus continuation payloads (onResponseReceivedActions).
     */
    fun parseFeed(json: JsonObject): Pair<List<VideoItem>, String?> {
        val videos = mutableListOf<VideoItem>()
        val tokenBox = TokenBox()

        // Initial page: twoColumnBrowseResultsRenderer -> tabs[selected] -> content
        json.pathArr("contents", "twoColumnBrowseResultsRenderer", "tabs")?.objects()?.forEach { tab ->
            val tabRenderer = tab.pathObj("tabRenderer") ?: return@forEach
            if (!tabRenderer.pathBool("selected")) return@forEach
            tabRenderer.pathArr("content", "richGridRenderer", "contents")?.let { walkItems(it, videos, tokenBox) }
            tabRenderer.pathArr("content", "sectionListRenderer", "contents")?.objects()?.forEach { section ->
                section.pathObj("continuationItemRenderer")?.let { tokenBox.value = continuationToken(it) }
                section.pathArr("itemSectionRenderer", "contents")?.objects()?.forEach { itemSection ->
                    itemSection.pathArr("gridRenderer", "items")?.let { walkItems(it, videos, tokenBox) }
                    itemSection.pathArr("playlistVideoListRenderer", "contents")?.let { walkItems(it, videos, tokenBox) }
                    itemSection.pathObj("videoRenderer")?.let { r -> videoFromVideoRenderer(r)?.let { videos += it } }
                }
            }
        }

        // Continuation pages
        json.pathArr("onResponseReceivedActions")?.objects()?.forEach { action ->
            action.pathArr("appendContinuationItemsAction", "continuationItems")?.let { walkItems(it, videos, tokenBox) }
        }
        json.pathArr("onResponseReceivedEndpoints")?.objects()?.forEach { endpoint ->
            endpoint.pathArr("appendContinuationItemsAction", "continuationItems")?.let { walkItems(it, videos, tokenBox) }
        }

        return videos.dedupById() to tokenBox.value
    }

    /** Walks one array of feed entries, collecting videos and the continuation token. */
    private fun walkItems(items: kotlinx.serialization.json.JsonArray, out: MutableList<VideoItem>, token: TokenBox) {
        items.objects().forEach { entry ->
            continuationToken(entry)?.let { token.value = it }
            videoFromItem(entry)?.let { out += it }
        }
    }

    private fun continuationToken(entry: JsonObject): String? =
        entry.pathStr("continuationItemRenderer", "continuationEndpoint", "continuationCommand", "token")
            ?: entry.pathStr("continuationItemRenderer", "button", "buttonRenderer", "command", "continuationCommand", "token")

    /** Maps a single feed entry (whatever its renderer family) to a [VideoItem]. */
    private fun videoFromItem(entry: JsonObject): VideoItem? {
        entry.pathObj("richItemRenderer", "content", "videoRenderer")?.let { return videoFromVideoRenderer(it) }
        entry.pathObj("richItemRenderer", "content", "reelItemRenderer")?.let { return videoFromReel(it) }
        entry.pathObj("richItemRenderer", "content", "shortsLockupViewModel")?.let { return videoFromShortsLockup(it) }
        entry.pathObj("gridVideoRenderer")?.let { return videoFromGrid(it) }
        entry.pathObj("playlistVideoRenderer")?.let { return videoFromPlaylistVideo(it) }
        entry.pathObj("videoRenderer")?.let { return videoFromVideoRenderer(it) }
        entry.pathObj("compactVideoRenderer")?.let { return videoFromCompact(it) }
        return null
    }

    // ------------------------------------------------------------ renderers

    private fun videoFromVideoRenderer(r: JsonObject): VideoItem? {
        val id = r.pathStr("videoId") ?: return null
        val isLive = r.pathArr("badges")?.objects()?.any {
            it.pathStr("metadataBadgeRenderer", "style") == "BADGE_STYLE_TYPE_LIVE_NOW"
        } || r.pathArr("thumbnailOverlays")?.objects()?.any {
            it.pathStr("thumbnailOverlayTimeStatusRenderer", "style") == "LIVE"
        }
        val channelRun = r.pathArr("ownerText", "runs")?.objects()?.firstOrNull()
        val channelName = channelRun?.pathStr("text")
            ?: r.pathStr("shortBylineText", "runs", "0", "text")
            ?: r.pathStr("longBylineText", "runs", "0", "text")
            ?: ""
        val channelId = channelRun?.pathStr("navigationEndpoint", "browseEndpoint", "browseId")
            ?: r.pathStr("shortBylineText", "runs", "0", "navigationEndpoint", "browseEndpoint", "browseId")
            ?: r.pathStr("longBylineText", "runs", "0", "navigationEndpoint", "browseEndpoint", "browseId")
            ?: ""
        return VideoItem(
            id = id,
            title = r.pathStr("title", "runs", "0", "text") ?: r.pathStr("title", "simpleText") ?: "",
            channelName = channelName,
            channelId = channelId,
            thumbnailUrl = pickThumbnail(r.pathArr("thumbnail", "thumbnails"), id),
            duration = r.pathStr("lengthText", "simpleText"),
            views = r.pathStr("shortViewCountText", "simpleText") ?: r.pathStr("viewCountText", "simpleText"),
            published = r.pathStr("publishedTimeText", "simpleText"),
            isLive = isLive
        )
    }

    private fun videoFromGrid(r: JsonObject): VideoItem? {
        val id = r.pathStr("videoId") ?: return null
        return VideoItem(
            id = id,
            title = r.pathStr("title", "simpleText") ?: r.pathStr("title", "runs", "0", "text") ?: "",
            channelName = r.pathStr("shortBylineText", "simpleText")
                ?: r.pathStr("shortBylineText", "runs", "0", "text") ?: "",
            channelId = r.pathStr("shortBylineText", "runs", "0", "navigationEndpoint", "browseEndpoint", "browseId") ?: "",
            thumbnailUrl = pickThumbnail(r.pathArr("thumbnail", "thumbnails"), id),
            duration = r.pathStr("lengthText", "simpleText") ?: r.pathStr("lengthText", "accessibility", "accessibilityData", "label"),
            views = r.pathStr("shortViewCountText", "simpleText") ?: r.pathStr("viewCountText", "simpleText"),
            published = r.pathStr("publishedTimeText", "simpleText")
        )
    }

    private fun videoFromPlaylistVideo(r: JsonObject): VideoItem? {
        val id = r.pathStr("videoId") ?: return null
        val duration = r.pathStr("lengthText", "simpleText")
            ?: r.pathArr("thumbnailOverlays")?.objects()?.firstNotNullOfOrNull {
                it.pathStr("thumbnailOverlayTimeStatusRenderer", "text", "simpleText")
            }
        return VideoItem(
            id = id,
            title = r.pathStr("title", "runs", "0", "text") ?: r.pathStr("title", "simpleText") ?: "",
            channelName = r.pathStr("shortBylineText", "runs", "0", "text")
                ?: r.pathStr("videoOwnerRenderer", "title", "runs", "0", "text") ?: "",
            channelId = r.pathStr("shortBylineText", "runs", "0", "navigationEndpoint", "browseEndpoint", "browseId") ?: "",
            thumbnailUrl = pickThumbnail(r.pathArr("thumbnail", "thumbnails"), id),
            duration = duration,
            views = r.pathStr("viewCountText", "simpleText") ?: r.pathStr("shortViewCountText", "simpleText"),
            published = r.pathStr("publishedTimeText", "simpleText")
        )
    }

    private fun videoFromCompact(r: JsonObject): VideoItem? {
        val id = r.pathStr("videoId") ?: return null
        return VideoItem(
            id = id,
            title = r.pathStr("title", "simpleText") ?: r.pathStr("title", "runs", "0", "text") ?: "",
            channelName = r.pathStr("shortBylineText", "simpleText")
                ?: r.pathStr("shortBylineText", "runs", "0", "text") ?: "",
            channelId = r.pathStr("shortBylineText", "runs", "0", "navigationEndpoint", "browseEndpoint", "browseId") ?: "",
            thumbnailUrl = pickThumbnail(r.pathArr("thumbnail", "thumbnails"), id),
            duration = r.pathStr("lengthText", "simpleText"),
            views = r.pathStr("shortViewCountText", "simpleText") ?: r.pathStr("viewCountText", "simpleText"),
            published = r.pathStr("publishedTimeText", "simpleText")
        )
    }

    private fun videoFromReel(r: JsonObject): VideoItem? {
        val id = r.pathStr("videoId") ?: return null
        return VideoItem(
            id = id,
            title = r.pathStr("headline", "simpleText") ?: "",
            channelName = r.pathStr("ownerText", "runs", "0", "text") ?: "",
            channelId = r.pathStr("ownerText", "runs", "0", "navigationEndpoint", "browseEndpoint", "browseId") ?: "",
            thumbnailUrl = pickThumbnail(r.pathArr("thumbnail", "thumbnails"), id),
            isShort = true
        )
    }

    private fun videoFromShortsLockup(vm: JsonObject): VideoItem? {
        val id = vm.pathStr("onTap", "innertubeCommand", "reelWatchEndpoint", "videoId")
            ?: vm.pathStr("entityId")?.substringAfter("shorts-")?.takeIf { it.isNotEmpty() }
            ?: return null
        return VideoItem(
            id = id,
            title = vm.pathStr("overlayMetadata", "primaryText", "content") ?: "",
            channelName = "",
            channelId = "",
            thumbnailUrl = pickThumbnail(vm.pathArr("thumbnail", "sources"), id),
            isShort = true
        )
    }

    private fun channelFromChannelRenderer(r: JsonObject): ChannelItem? {
        val id = r.pathStr("channelId") ?: r.pathStr("navigationEndpoint", "browseEndpoint", "browseId") ?: return null
        return ChannelItem(
            id = id,
            name = r.pathStr("title", "simpleText") ?: r.pathStr("title", "runs", "0", "text") ?: "",
            avatarUrl = pickAvatar(r.pathArr("thumbnail", "thumbnails")),
            subscribers = r.pathStr("subscriberCountText", "simpleText")
                ?: r.pathStr("videoCountText", "simpleText")
        )
    }

    private fun pickThumbnail(thumbs: kotlinx.serialization.json.JsonArray?, videoId: String): String {
        if (!thumbs.isNullOrEmpty()) {
            // Prefer ~640px-wide entries (sharp on phones, cheap to decode); fall back to the largest.
            val entries = thumbs.objects()
            entries.firstOrNull { (it.pathInt("width") ?: 0) in 320..853 }
                ?.pathStr("url")
                ?.let { return it }
            entries.lastOrNull()?.pathStr("url")?.let { return it }
        }
        return "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
    }

    private fun pickAvatar(thumbs: kotlinx.serialization.json.JsonArray?): String? {
        if (thumbs.isNullOrEmpty()) return null
        val entries = thumbs.objects()
        return entries.firstOrNull { (it.pathInt("width") ?: 0) >= 100 }?.pathStr("url")
            ?: entries.lastOrNull()?.pathStr("url")
    }

    // ---------------------------------------------------------------- search

    fun parseSearch(json: JsonObject): Triple<List<VideoItem>, List<ChannelItem>, String?> {
        val videos = mutableListOf<VideoItem>()
        val channels = mutableListOf<ChannelItem>()
        val tokenBox = TokenBox()

        fun walk(contents: kotlinx.serialization.json.JsonArray) {
            contents.objects().forEach { entry ->
                continuationToken(entry)?.let { tokenBox.value = it }
                entry.pathObj("videoRenderer")?.let { r -> videoFromVideoRenderer(r)?.let { videos += it } }
                entry.pathObj("channelRenderer")?.let { c -> channelFromChannelRenderer(c)?.let { channels += it } }
                entry.pathObj("compactVideoRenderer")?.let { r -> videoFromCompact(r)?.let { videos += it } }
                entry.pathObj("reelItemRenderer")?.let { r -> videoFromReel(r)?.let { videos += it } }
            }
        }

        json.pathArr("contents", "twoColumnSearchResultsRenderer", "primaryContents", "sectionListRenderer", "contents")
            ?.objects()?.forEach { section ->
                section.pathObj("continuationItemRenderer")?.let { tokenBox.value = continuationToken(it) }
                section.pathArr("itemSectionRenderer", "contents")?.let { walk(it) }
            }
        json.pathArr("onResponseReceivedActions")?.objects()?.forEach { action ->
            action.pathArr("appendContinuationItemsAction", "continuationItems")?.let { walk(it) }
        }
        return Triple(videos.dedupById(), channels, tokenBox.value)
    }

    // ------------------------------------------------------------ watch next

    fun parseWatchNext(json: JsonObject): WatchNextInfo {
        var commentsToken: String? = null
        json.pathArr("contents", "twoColumnWatchNextResults", "results", "results", "contents")
            ?.objects()?.forEach { content ->
                val section = content.pathObj("itemSectionRenderer") ?: return@forEach
                if (section.pathStr("sectionIdentifier") == "comment-item-section") {
                    commentsToken = section.pathArr("contents")?.objects()?.firstNotNullOfOrNull {
                        it.pathStr("continuationItemRenderer", "continuationEndpoint", "continuationCommand", "token")
                    }
                }
            }
        // Fallback: locate the comment item-section anywhere in the tree.
        if (commentsToken == null) {
            commentsToken = json.deepFind { it.pathStr("sectionIdentifier") == "comment-item-section" }
                ?.pathArr("contents")?.objects()?.firstNotNullOfOrNull {
                    it.pathStr("continuationItemRenderer", "continuationEndpoint", "continuationCommand", "token")
                }
        }

        // Subscribe state: any subscribeButtonRenderer with subscribed=true, or the
        // presence of a notification-preference button (shown only to subscribers).
        val subscribed = json.deepFind { obj ->
            (obj["subscribeButtonRenderer"] != null && obj.pathBool("subscribed")) ||
                obj["notificationPreferenceButtonRenderer"] != null
        } != null

        return WatchNextInfo(commentsToken = commentsToken, isSubscribed = subscribed)
    }

    // --------------------------------------------------------------- comments

    /**
     * Parses the modern comments payload:
     *  - `onResponseReceivedEndpoints[].reload/appendContinuationItems.continuationItems[]`
     *  - `commentThreadRenderer` -> `commentViewModel.commentViewModel` (keys only)
     *  - entity payloads under `frameworkUpdates.entityBatchUpdate.mutations[]`
     *  - legacy `comment.commentRenderer` fallback
     */
    fun parseComments(json: JsonObject): CommentsPage {
        val items = mutableListOf<CommentItem>()
        val tokenBox = TokenBox()
        var createParams: String? = null
        val sorts = mutableListOf<CommentSortOption>()

        // entityKey -> mutation payload object
        val mutations = json.pathArr("frameworkUpdates", "entityBatchUpdate", "mutations")?.objects()?.orEmpty()
        val payloadByKey = HashMap<String, JsonObject>(mutations.size * 2)
        mutations.forEach { mutation ->
            mutation.pathStr("entityKey")?.let { key -> payloadByKey[key] = mutation }
        }

        fun itemFromViewModel(vm: JsonObject, repliesToken: String?): CommentItem? {
            val commentId = vm.pathStr("commentId") ?: return null
            val commentKey = vm.pathStr("commentKey")
            val toolbarStateKey = vm.pathStr("toolbarStateKey")
            val toolbarSurfaceKey = vm.pathStr("toolbarSurfaceKey")

            val payload = commentKey?.let { payloadByKey[it] }
            val commentEntity = payload?.pathObj("payload", "commentEntityPayload")

            val toolbar = commentEntity?.pathObj("toolbar")
            val surface = toolbarSurfaceKey?.let { payloadByKey[it] }?.pathObj("payload", "engagementToolbarSurfaceEntityPayload")
            val stateEntity = toolbarStateKey?.let { payloadByKey[it] }?.pathObj("payload", "engagementToolbarStateEntityPayload")

            val avatarUrl = commentEntity?.pathArr("avatar", "image", "sources")?.objects()?.firstOrNull()
                ?.pathStr("url")
                ?: commentEntity?.pathArr("avatar", "image", "thumbnails")?.objects()?.firstOrNull()?.pathStr("url")

            val likeState = stateEntity?.pathStr("likeState")
            val heartState = stateEntity?.pathStr("heartState")

            return CommentItem(
                id = commentId,
                authorName = commentEntity?.pathStr("author", "displayName") ?: "",
                authorId = commentEntity?.pathStr("author", "channelId"),
                authorHandle = commentEntity?.pathStr("author", "channelCommand", "innertubeCommand", "browseEndpoint", "canonicalBaseUrl"),
                avatarUrl = avatarUrl,
                text = commentEntity?.pathStr("properties", "content", "content") ?: "",
                publishedTime = commentEntity?.pathStr("properties", "publishedTime"),
                likeCount = toolbar?.pathStr("likeCountNotliked") ?: toolbar?.pathStr("likeCountLiked"),
                replyCount = toolbar?.pathStr("replyCount")?.trim()?.toIntOrNull() ?: 0,
                isLiked = likeState == "TOOLBAR_LIKE_STATE_LIKED",
                isHearted = heartState == "TOOLBAR_HEART_STATE_HEARTED",
                isCreator = false,
                repliesToken = repliesToken,
                likeActionToken = surface?.pathStr("likeCommand", "innertubeCommand", "performCommentActionEndpoint", "action"),
                unlikeActionToken = surface?.pathStr("unlikeCommand", "innertubeCommand", "performCommentActionEndpoint", "action")
            )
        }

        fun itemFromLegacyRenderer(cr: JsonObject, repliesToken: String?): CommentItem? {
            val id = cr.pathStr("commentId") ?: return null
            return CommentItem(
                id = id,
                authorName = cr.pathStr("authorText", "simpleText") ?: "",
                authorId = cr.pathStr("authorEndpoint", "browseEndpoint", "browseId"),
                authorHandle = cr.pathStr("authorEndpoint", "browseEndpoint", "canonicalBaseUrl"),
                avatarUrl = pickAvatar(cr.pathArr("authorThumbnail", "thumbnails")),
                text = cr.pathArr("contentText", "runs")?.objects()?.joinToString("") { it.pathStr("text").orEmpty() } ?: "",
                publishedTime = cr.pathStr("publishedTimeText", "simpleText"),
                likeCount = cr.pathStr("voteCountText", "simpleText"),
                replyCount = cr.pathStr("replyCount", "text", "simpleText")?.trim()?.toIntOrNull() ?: 0,
                isLiked = cr.pathBool("isLiked"),
                repliesToken = repliesToken,
                likeActionToken = null,
                unlikeActionToken = null
            )
        }

        json.pathArr("onResponseReceivedEndpoints")?.objects()?.forEach { endpoint ->
            val contents = endpoint.pathArr("reloadContinuationItemsCommand", "continuationItems")
                ?: endpoint.pathArr("appendContinuationItemsAction", "continuationItems")
                ?: return@forEach
            contents.objects().forEach { entry ->
                continuationToken(entry)?.let { tokenBox.value = it }
                val thread = entry.pathObj("commentThreadRenderer")
                if (thread != null) {
                    val repliesToken = thread.pathArr("replies", "commentRepliesRenderer", "contents")?.objects()
                        ?.firstOrNull { it.pathObj("continuationItemRenderer") != null }
                        ?.let { continuationToken(it) }
                    val modern = thread.pathObj("commentViewModel", "commentViewModel")
                    val item = modern?.let { itemFromViewModel(it, repliesToken) }
                        ?: thread.pathObj("comment", "commentRenderer")?.let { itemFromLegacyRenderer(it, repliesToken) }
                    if (item != null) items += item
                    return@forEach
                }
                entry.pathObj("commentViewModel")?.let { vm ->
                    itemFromViewModel(vm, null)?.let { items += it }
                    return@forEach
                }
                val header = entry.pathObj("commentsHeaderRenderer")
                if (header != null) {
                    createParams = header.pathStr(
                        "createRenderer", "commentSimpleboxRenderer", "submitButton", "buttonRenderer",
                        "serviceEndpoint", "createCommentEndpoint", "createCommentParams"
                    )
                    header.pathArr("sortMenu", "sortFilterSubMenuRenderer", "subMenuItems")?.objects()?.forEach { sort ->
                        val title = sort.pathStr("title") ?: return@forEach
                        val token = sort.pathStr("serviceEndpoint", "continuationCommand", "token") ?: return@forEach
                        sorts += CommentSortOption(title = title, token = token, selected = sort.pathBool("selected"))
                    }
                }
            }
        }

        return CommentsPage(
            items = items.toImmutableList(),
            continuation = tokenBox.value,
            createCommentParams = createParams,
            sorts = sorts.toImmutableList()
        )
    }

    // ----------------------------------------------------------------- utils

    private class TokenBox {
        var value: String? = null
    }

    private fun List<VideoItem>.dedupById(): List<VideoItem> {
        val seen = HashSet<String>(size)
        return filterTo(ArrayList(size)) { seen.add(it.id) }
    }
}
