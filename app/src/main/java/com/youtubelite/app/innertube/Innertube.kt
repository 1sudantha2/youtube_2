package com.youtubelite.app.innertube

import com.youtubelite.app.AppGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Minimal InnerTube (youtubei) client. WEB client context + SAPISIDHASH auth.
 * Responses are returned as raw JsonObject and traversed with the string-indexed
 * helpers in util/Jsonx.kt — no giant DTO graph, minimal GC churn.
 */
object Innertube {

    private const val BASE = "https://www.youtube.com/youtubei/v1"
    private const val CLIENT_NAME = "WEB"
    private const val CLIENT_VERSION = "2.20240816.01.00"
    private val JSON_MEDIA = "application/json".toMediaType()

    suspend fun browse(browseId: String, continuation: String? = null): JsonObject =
        post("browse") {
            put("context", contextJson())
            put("browseId", JsonPrimitive(browseId))
            if (continuation != null) put("continuation", JsonPrimitive(continuation))
        }

    suspend fun next(videoId: String? = null, continuation: String? = null): JsonObject =
        post("next") {
            put("context", contextJson())
            if (videoId != null) put("videoId", JsonPrimitive(videoId))
            if (continuation != null) put("continuation", JsonPrimitive(continuation))
        }

    suspend fun search(query: String, continuation: String? = null): JsonObject =
        post("search") {
            put("context", contextJson())
            put("query", JsonPrimitive(query))
            if (continuation != null) put("continuation", JsonPrimitive(continuation))
        }

    suspend fun like(videoId: String): JsonObject = likeAction("like/like", videoId)

    suspend fun dislike(videoId: String): JsonObject = likeAction("like/dislike", videoId)

    suspend fun removeLike(videoId: String): JsonObject = likeAction("like/removelike", videoId)

    suspend fun subscribe(channelId: String): JsonObject = post("subscription/subscribe") {
        put("context", contextJson())
        putJsonArray("channelIds") { add(JsonPrimitive(channelId)) }
    }

    suspend fun unsubscribe(channelId: String): JsonObject = post("subscription/unsubscribe") {
        put("context", contextJson())
        putJsonArray("channelIds") { add(JsonPrimitive(channelId)) }
    }

    suspend fun createComment(videoId: String, channelId: String, text: String): JsonObject =
        post("comment/create_comment") {
            put("context", contextJson())
            put("videoId", JsonPrimitive(videoId))
            put("channelId", JsonPrimitive(channelId))
            put("commentText", JsonPrimitive(text))
        }

    // ------------------------------------------------------------------ //

    private suspend fun likeAction(path: String, videoId: String): JsonObject = post(path) {
        put("context", contextJson())
        putJsonObject("target") { put("videoId", JsonPrimitive(videoId)) }
    }

    private fun contextJson() = buildJsonObject {
        putJsonObject("client") {
            put("clientName", JsonPrimitive(CLIENT_NAME))
            put("clientVersion", JsonPrimitive(CLIENT_VERSION))
            put("hl", JsonPrimitive("en"))
            put("gl", JsonPrimitive("US"))
        }
    }

    private suspend fun post(endpoint: String, body: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject =
        withContext(Dispatchers.IO) {
            val payload = buildJsonObject { body() }.toString()
            val request = Request.Builder()
                .url("$BASE/$endpoint?prettyPrint=false")
                .header("X-Youtube-Client-Name", "1")
                .header("X-Youtube-Client-Version", CLIENT_VERSION)
                .header("X-Origin", "https://www.youtube.com")
                .post(payload.toRequestBody(JSON_MEDIA))
                .build()
            AppGraph.apiClient.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                check(response.isSuccessful) { "HTTP ${response.code} from $endpoint" }
                AppGraph.json.parseToJsonElement(text) as JsonObject
            }
        }
}
