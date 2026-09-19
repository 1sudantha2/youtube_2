package app.you.tube.core.network

import app.you.tube.core.auth.AuthManager
import app.you.tube.core.auth.Sapisid
import app.you.tube.core.util.asPrimitiveString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder

/** Engagement action for `/youtubei/v1/like/...`. */
enum class LikeAction(val path: String) {
    LIKE("like/like"),
    DISLIKE("like/dislike"),
    REMOVE("like/removelike")
}

/**
 * Thin, allocation-conscious InnerTube client (youtubei/v1).
 *
 * Protocol facts verified against live web clients (Feb 2026):
 *  - WEB client `2.20260213.01.00` needs **no API key** query parameter.
 *  - Video like/dislike/remove: `{"target": {"videoId": ...}}`.
 *  - Subscribe/unsubscribe: `{"channelIds": [...], "params": "EgIIAhgA"/"CgIIAhgA"}`.
 *  - Comment creation: `{"createCommentParams": <token from comments response>,
 *    "commentText": ...}` — the token arrives with the first comments page.
 *  - Comment like: `/comment/perform_comment_action` with the per-comment
 *    action token.
 *
 * All calls run on [Dispatchers.IO] through the shared OkHttp client.
 */
class InnerTubeClient(
    private val okHttp: OkHttpClient,
    private val auth: AuthManager
) {

    suspend fun browse(
        browseId: String? = null,
        continuation: String? = null,
        params: String? = null
    ): JsonObject = call("browse", buildJsonObject {
        put("context", webContext())
        browseId?.let { put("browseId", it) }
        continuation?.let { put("continuation", it) }
        params?.let { put("params", it) }
    })

    suspend fun next(videoId: String): JsonObject = call("next", buildJsonObject {
        put("context", webContext())
        put("videoId", videoId)
    })

    suspend fun search(query: String, continuation: String? = null): JsonObject = call("search", buildJsonObject {
        put("context", webContext())
        put("query", query)
        continuation?.let { put("continuation", it) }
    })

    suspend fun like(videoId: String, action: LikeAction): Boolean =
        call(action.path, buildJsonObject {
            put("context", webContext())
            put("target", buildJsonObject { put("videoId", videoId) })
        }).let { true }

    suspend fun subscribe(channelId: String, subscribe: Boolean): Boolean =
        call(if (subscribe) "subscription/subscribe" else "subscription/unsubscribe", buildJsonObject {
            put("context", webContext())
            putJsonArray("channelIds") { add(channelId) }
            put("params", if (subscribe) "EgIIAhgA" else "CgIIAhgA")
        }).let { true }

    suspend fun createComment(createCommentParams: String, text: String): Boolean =
        call("comment/create_comment", buildJsonObject {
            put("context", webContext())
            put("createCommentParams", createCommentParams)
            put("commentText", text)
        }).let { true }

    suspend fun createCommentReply(createReplyParams: String, text: String): Boolean =
        call("comment/create_comment_reply", buildJsonObject {
            put("context", webContext())
            put("createReplyParams", createReplyParams)
            put("replyText", text)
        }).let { true }

    suspend fun performCommentAction(actionToken: String): Boolean =
        call("comment/perform_comment_action", buildJsonObject {
            put("context", webContext())
            putJsonArray("actions") { add(actionToken) }
        }).let { true }

    /** Google's public suggest endpoint (plain JSON with client=firefox). */
    suspend fun suggestions(query: String): List<String> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val url = "https://suggestqueries.google.com/complete/search?client=firefox&ds=yt&q=" +
            URLEncoder.encode(query, "UTF-8")
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        runCatching {
            okHttp.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList<String>()
                val body = response.body?.string().orEmpty()
                val root = Json.parseToJsonElement(body).jsonArray
                root.getOrNull(1)?.jsonArray
                    ?.mapNotNull { it.asPrimitiveString() }
                    .orEmpty()
            }
        }.getOrDefault(emptyList())
    }

    private suspend fun call(endpoint: String, body: JsonObject): JsonObject = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$BASE/$endpoint?prettyPrint=false")
            .headers(requestHeaders())
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        okHttp.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("InnerTube $endpoint failed: HTTP ${response.code} ${text.take(160)}")
            }
            Json.parseToJsonElement(text) as? JsonObject
                ?: throw IOException("InnerTube $endpoint: unexpected response shape")
        }
    }

    private fun requestHeaders(): Headers {
        val builder = Headers.Builder()
            .add("Content-Type", "application/json")
            .add("User-Agent", USER_AGENT)
            .add("Accept-Language", "en-US,en;q=0.9")
            .add("Origin", Sapisid.ORIGIN)
            .add("X-Origin", Sapisid.ORIGIN)
            .add("Referer", "https://www.youtube.com/")
        auth.cookieHeader()?.let { builder.add("Cookie", it) }
        auth.authorizationHeaderValue()?.let { builder.add("Authorization", it) }
        return builder.build()
    }

    companion object {
        const val BASE = "https://www.youtube.com/youtubei/v1"

        /** Current WEB InnerTube client (kept in sync with live web clients). */
        const val WEB_CLIENT_VERSION = "2.20260213.01.00"

        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        fun webContext(): JsonObject = buildJsonObject {
            put("context", buildJsonObject {
                put("client", buildJsonObject {
                    put("hl", "en")
                    put("gl", "US")
                    put("clientName", "WEB")
                    put("clientVersion", WEB_CLIENT_VERSION)
                    put("userAgent", USER_AGENT)
                    put("platform", "DESKTOP")
                    put("clientFormFactor", "UNKNOWN_FORM_FACTOR")
                    put("userInterfaceTheme", "USER_INTERFACE_THEME_DARK")
                    put("browserName", "Chrome")
                    put("browserVersion", "131.0.0.0")
                    put("utcOffsetMinutes", 0)
                    put("mainAppWebInfo", buildJsonObject {
                        put("graftUrl", "/")
                        put("webDisplayMode", "WEB_DISPLAY_MODE_BROWSER")
                        put("isWebNativeShareAvailable", true)
                    })
                })
                put("user", buildJsonObject { put("lockedSafetyMode", false) })
                put("request", buildJsonObject {
                    put("useSsl", true)
                    put("internalExperimentFlags", JsonArray(emptyList()))
                    put("consistencyTokenJars", JsonArray(emptyList()))
                })
            })
        }
    }
}
