package io.github.sudantha.youtubelite.data

import kotlinx.collections.immutable.toPersistentList
import kotlinx.serialization.json.*

/** Small, bounded tree walk; normalize immediately and never retain the JSON tree in UI state. */
object YouTubeParser {
    fun objects(root: JsonElement, key: String): Sequence<JsonObject> = sequence {
        suspend fun SequenceScope<JsonObject>.walk(node: JsonElement, depth: Int) {
            if (depth > 64) return
            when (node) {
                is JsonObject -> {
                    (node[key] as? JsonObject)?.let { yield(it) }
                    node.values.forEach { walk(it, depth + 1) }
                }
                is JsonArray -> node.forEach { walk(it, depth + 1) }
                else -> Unit
            }
        }
        walk(root, 0)
    }
    fun text(node: JsonElement?): String = when (node) {
        is JsonPrimitive -> node.contentOrNull.orEmpty()
        is JsonObject -> node.str("simpleText").ifEmpty { node.str("content") }.ifEmpty {
            (node["runs"] as? JsonArray)?.joinToString("") { (it as? JsonObject)?.str("text").orEmpty() }.orEmpty()
        }
        else -> ""
    }
    fun continuation(root: JsonElement): String? = objects(root, "continuationItemRenderer")
        .flatMap { objects(it, "continuationCommand") }.map { it.str("token") }
        .firstOrNull { it.isNotBlank() }
        ?: objects(root, "nextContinuationData").map { it.str("continuation") }.firstOrNull { it.isNotBlank() }

    fun videos(root: JsonElement): VideoPage {
        val videos = LinkedHashMap<String, Video>()
        for (kind in listOf("videoRenderer", "gridVideoRenderer", "compactVideoRenderer", "playlistVideoRenderer")) {
            objects(root, kind).forEach { item ->
                val id = item.str("videoId")
                if (validVideoId(id) && videos.size < 120) {
                    val byline = item["ownerText"] ?: item["shortBylineText"] ?: item["longBylineText"]
                    videos.putIfAbsent(id, Video(id, text(item["title"]), thumbnail(item["thumbnail"]),
                        text(byline), byline?.let { objects(it, "browseEndpoint").firstOrNull()?.str("browseId") }.orEmpty(),
                        text(item["lengthText"]), text(item["shortViewCountText"] ?: item["viewCountText"])))
                }
            }
        }
        objects(root, "richItemRenderer").forEach { item ->
            val id = item.str("contentId")
            if (validVideoId(id) && videos.size < 120) {
                val lockup = (item["content"] as? JsonObject)?.get("lockupViewModel") as? JsonObject
                val metadata = (lockup?.get("metadata") as? JsonObject)?.get("lockupMetadataViewModel") as? JsonObject
                val image = (lockup?.get("image") as? JsonObject)?.get("thumbnailViewModel") as? JsonObject
                val url = (image?.get("thumbnail") as? JsonObject)?.let { thumbnail(it) }
                val channel = (metadata?.get("metadataRows") as? JsonArray)
                    ?.joinToString(" · ") { row ->
                        ((row as? JsonObject)?.get("metadataParts") as? JsonArray)
                            ?.mapNotNull { (it as? JsonObject)?.get("text") as? JsonObject }
                            ?.joinToString("") { (it["content"] as? JsonPrimitive)?.contentOrNull.orEmpty() }
                            .orEmpty()
                    }
                    ?.filter(String::isNotBlank)
                    .orEmpty()
                videos.putIfAbsent(id, Video(id, text((lockup?.get("textViewModel") as? JsonObject)?.get("title")),
                    url, channel = channel))
            }
        }
        return VideoPage(videos.values.toPersistentList(), continuation(root))
    }
    fun comments(root: JsonElement): CommentPage {
        val result = LinkedHashMap<String, Comment>()
        objects(root, "commentRenderer").forEach {
            val id = it.str("commentId")
            if (id.isNotEmpty()) result[id] = Comment(id, text(it["authorText"]), text(it["contentText"]), text(it["voteCount"]))
        }
        objects(root, "commentEntityPayload").forEach {
            val properties = it["properties"] as? JsonObject
            val author = it["author"] as? JsonObject
            val id = properties?.str("commentId").orEmpty()
            if (id.isNotBlank()) result[id] = Comment(id, author?.str("displayName").orEmpty(),
                text(properties?.get("content")), (it["toolbar"] as? JsonObject)?.str("likeCountNotliked").orEmpty())
        }
        return CommentPage(result.values.take(100).toPersistentList(), continuation(root), commentParams(root))
    }
    fun commentParams(root: JsonElement): String? = objects(root, "createCommentEndpoint")
        .map { it.str("createCommentParams") }.firstOrNull { it.isNotBlank() }

    fun watch(root: JsonElement): WatchDetails {
        val related = objects(root, "secondaryResults").lastOrNull()
        val section = objects(root, "itemSectionRenderer").firstOrNull {
            it.str("sectionIdentifier").startsWith("comment") || it.str("targetId").startsWith("comment")
        } ?: objects(root, "engagementPanelSectionListRenderer").firstOrNull {
            it.str("targetId").contains("comments")
        }
        val token = section?.let(::continuation)
            ?: objects(root, "commentsEntryPointHeaderRenderer").firstNotNullOfOrNull { header ->
                objects(header, "continuationCommand").firstOrNull()?.str("token")
            }
        val subscribed = objects(root, "subscribeButtonRenderer").firstOrNull()?.get("subscribed")
            ?.let { (it as? JsonPrimitive)?.booleanOrNull }
        return WatchDetails(related?.let(::videos) ?: VideoPage(), token, commentParams(root), subscribed)
    }
    private fun thumbnail(node: JsonElement?): String {
        val obj = node as? JsonObject ?: return ""
        val candidates = (obj["thumbnails"] ?: obj["sources"]) as? JsonArray ?: return ""
        // Prefer a modest thumbnail; Coil will still decode to actual target dimensions.
        return candidates.mapNotNull { it as? JsonObject }
            .minByOrNull { kotlin.math.abs(((it["width"] as? JsonPrimitive)?.intOrNull ?: 480) - 480) }
            ?.str("url").orEmpty().let { if (it.startsWith("//")) "https:$it" else it }
    }
    fun validVideoId(id: String): Boolean = id.matches(Regex("[A-Za-z0-9_-]{11}"))
}
internal fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
