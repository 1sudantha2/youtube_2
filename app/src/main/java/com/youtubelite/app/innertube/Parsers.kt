package com.youtubelite.app.innertube

import com.youtubelite.app.model.Comment
import com.youtubelite.app.model.QualityOption
import com.youtubelite.app.model.Video
import com.youtubelite.app.util.asArr
import com.youtubelite.app.util.asObj
import com.youtubelite.app.util.dig
import com.youtubelite.app.util.digStr
import com.youtubelite.app.util.findAll
import com.youtubelite.app.util.findFirstBool
import com.youtubelite.app.util.findNextToken
import com.youtubelite.app.util.getA
import com.youtubelite.app.util.getO
import com.youtubelite.app.util.getS
import com.youtubelite.app.util.runsText
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * Feed / comment extraction from InnerTube responses. Every renderer type the
 * app supports is collected in one pre-order pass so shelf reshuffles cannot
 * break ordering.
 */
object YtParsers {

    private val RENDERERS = setOf(
        "videoRenderer",
        "gridVideoRenderer",
        "compactVideoRenderer",
        "playlistVideoRenderer",
        "reelItemRenderer",
        "shortsLockupViewModel",
        "lockupViewModel",
    )

    fun parseVideos(root: JsonObject): Pair<List<Video>, String?> {
        val out = LinkedHashMap<String, Video>(64)
        for ((type, wrapper) in findAllAny(root)) {
            val o = wrapper[type].asObj() ?: wrapper
            when (type) {
                "reelItemRenderer" -> parseReel(o)
                "shortsLockupViewModel" -> parseShortsLockup(o)
                "lockupViewModel" -> parseLockup(o)
                else -> parseRenderer(o)
            }?.let { v -> out.putIfAbsent(v.id, v) }
        }
        return out.values.toList() to root.findNextToken()
    }

    private fun findAllAny(root: JsonObject): List<Pair<String, JsonObject>> {
        val out = ArrayList<Pair<String, JsonObject>>(64)
        val stack = ArrayDeque<JsonObject>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val el = stack.removeLast()
            var matched = false
            for (t in RENDERERS) {
                if (el.containsKey(t)) {
                    out.add(t to el)
                    matched = true
                    break
                }
            }
            // Do not descend into a matched renderer body (avoids duplicates).
            val kids = ArrayList<JsonObject>(el.size)
            for ((k, v) in el) {
                if (matched && k in RENDERERS) continue
                val o = v as? JsonObject
                if (o != null) kids.add(o)
                else if (v is JsonArray) for (item in v) (item as? JsonObject)?.let(kids::add)
            }
            for (i in kids.indices.reversed()) stack.addLast(kids[i])
        }
        return out
    }

    private fun parseRenderer(o: JsonObject): Video? {
        val id = o.getS("videoId") ?: return null
        val title = runsText(o.getO("title")) ?: return null
        val byline = o.getO("ownerText") ?: o.getO("longBylineText") ?: o.getO("shortBylineText")
        val views = runsText(o.getO("viewCountText")).orEmpty()
        val duration = runsText(o.getO("lengthText")).orEmpty()
        val live = o.getA("badges")?.any {
            it.asObj()?.digStr("metadataBadgeRenderer", "style")?.contains("LIVE") == true
        } == true || views.contains("watching")
        return Video(
            id = id,
            title = title,
            channel = runsText(byline).orEmpty(),
            channelId = byline?.getA("runs")?.firstOrNull()?.asObj()
                ?.digStr("navigationEndpoint", "browseEndpoint", "browseId").orEmpty(),
            views = views,
            published = runsText(o.getO("publishedTimeText")).orEmpty(),
            duration = if (live) "" else duration,
            thumb = thumbOf(o.getO("thumbnail"), id),
            isLive = live,
        )
    }

    private fun parseReel(o: JsonObject): Video? {
        val id = o.getS("videoId") ?: return null
        return Video(
            id = id,
            title = o.digStr("headline", "simpleText").orEmpty(),
            channel = "",
            channelId = "",
            views = runsText(o.getO("viewCountText")).orEmpty(),
            published = "",
            duration = "",
            thumb = thumbOf(o.getO("thumbnail"), id),
        )
    }

    private fun parseShortsLockup(o: JsonObject): Video? {
        val id = o.digStr("onTap", "innertubeCommand", "reelWatchEndpoint", "videoId") ?: return null
        return Video(
            id = id,
            title = o.digStr("overlayMetadata", "primaryText", "content").orEmpty(),
            channel = "",
            channelId = "",
            views = o.digStr("overlayMetadata", "secondaryText", "content").orEmpty(),
            published = "",
            duration = "",
            thumb = thumbOf(o.getO("thumbnail"), id),
        )
    }

    private fun parseLockup(o: JsonObject): Video? {
        if (o.digStr("contentType") != "VIDEO") return null
        val id = o.getS("contentId") ?: return null
        // Duration lives in the thumbnail overlay badges, if present.
        val duration = o.getO("contentImage")
            ?.dig("thumbnailViewModel", "overlay", "thumbnailOverlayBadgeViewModel",
                "thumbnailBadges")
            ?.asArr()?.firstOrNull()?.asObj()
            ?.digStr("thumbnailBadgeViewModel", "text").orEmpty()
        val metadataRows = o.getO("metadata")?.getO("lockupMetadataViewModel")
            ?.getO("metadata")?.getO("contentMetadataViewModel")?.getA("metadataRows")
        val channel = metadataRows?.firstOrNull()?.asObj()?.getA("metadataParts")
            ?.firstOrNull()?.asObj()?.digStr("text", "content").orEmpty()
        val views = metadataRows?.lastOrNull()?.asObj()?.getA("metadataParts")
            ?.firstOrNull()?.asObj()?.digStr("text", "content")
            ?.takeIf { it.contains("view", ignoreCase = true) }.orEmpty()
        return Video(
            id = id,
            title = o.digStr("metadata", "lockupMetadataViewModel", "title", "content").orEmpty(),
            channel = channel,
            channelId = "",
            views = views,
            published = "",
            duration = duration,
            thumb = thumbOf(o.getO("contentImage"), id),
        )
    }

    private fun thumbOf(o: JsonObject?, id: String): String =
        o?.getA("thumbnails")?.lastOrNull()?.asObj()?.getS("url")
            ?: o?.dig("thumbnailViewModel", "image", "sources")
                ?.asArr()?.lastOrNull()?.asObj()?.getS("url")
            ?: "https://i.ytimg.com/vi/$id/mqdefault.jpg"

    // --------------------------------------------------------------- //
    // Comments
    // --------------------------------------------------------------- //

    /** Continuation token that bootstraps the comment thread for a video. */
    fun findCommentToken(root: JsonObject): String? {
        for (isr in root.findAll("itemSectionRenderer")) {
            if (isr.digStr("sectionIdentifier") == "comment-item-section") {
                isr.findNextToken()?.let { return it }
            }
        }
        return root.findAll("engagementPanelSectionListRenderer")
            .firstOrNull { it.getS("panelIdentifier") == "comment-item-section" }
            ?.findNextToken()
    }

    fun parseCommentsPage(root: JsonObject): Pair<List<Comment>, String?> {
        // Payload registry: comment key -> commentEntityPayload.
        val entities = HashMap<String, JsonObject>(32)
        for (wrapper in root.findAll("commentEntityPayload")) {
            val payload = wrapper["commentEntityPayload"].asObj() ?: wrapper
            payload.getS("key")?.let { entities[it] = payload }
        }

        val comments = ArrayList<Comment>(32)
        var token: String? = null
        val actions = root.getA("onResponseReceivedActions") ?: JsonArray(emptyList())
        for (action in actions) {
            val a = action.asObj() ?: continue
            val items = a.dig("reloadContinuationItemsCommand", "continuationItems").asArr()
                ?: a.dig("appendContinuationItemsAction", "continuationItems").asArr()
                ?: continue
            for (item in items) {
                val io = item.asObj() ?: continue
                when {
                    io.containsKey("commentEntityPayload") -> {
                        io["commentEntityPayload"].asObj()?.let { p ->
                            toComment(p)?.let(comments::add)
                        }
                    }
                    io.containsKey("commentThreadRenderer") -> {
                        val key = io.digStr("commentThreadRenderer", "commentViewModel", "commentKey")
                            ?: io.digStr("commentThreadRenderer", "commentRenderer", "commentId")
                        val p = key?.let { entities[it] }
                        p?.let { toComment(it)?.let(comments::add) }
                    }
                    io.containsKey("continuationItemRenderer") -> {
                        token = io.digStr("continuationItemRenderer", "continuationEndpoint",
                            "continuationCommand", "token") ?: token
                    }
                }
            }
        }

        if (comments.isEmpty() && entities.isNotEmpty()) {
            for ((_, payload) in entities) toComment(payload)?.let(comments::add)
        }
        return comments to token
    }

    private fun toComment(p: JsonObject): Comment? {
        val text = p.digStr("properties", "content", "content") ?: return null
        return Comment(
            id = p.getS("key")
                ?: p.digStr("properties", "commentId")
                ?: text.hashCode().toString(),
            author = p.digStr("author", "displayName").orEmpty(),
            avatar = p.digStr("author", "avatarThumbnail", "url").orEmpty(),
            text = text,
            published = p.digStr("properties", "publishedTime").orEmpty(),
            likes = p.digStr("toolbar", "likeCountNotliked").orEmpty(),
        )
    }

    // --------------------------------------------------------------- //
    // Watch-page state heuristics
    // --------------------------------------------------------------- //

    /** Best-effort like state from the watch page button tree. */
    fun findLikeState(root: JsonObject): Boolean? {
        for (el in root.findAll("statefulButtonViewModel")) {
            when (el.digStr("statefulButtonViewModel", "state")) {
                "LIKED" -> return true
                "DISLIKED" -> return false
            }
        }
        return null
    }

    /** Best-effort subscription state — first `subscribed` boolean in the tree. */
    fun findSubscribedState(root: JsonObject): Boolean =
        root.findFirstBool("subscribed") ?: false
}
