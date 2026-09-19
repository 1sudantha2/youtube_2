package io.github.sudantha.youtubelite.data

import io.github.sudantha.youtubelite.auth.SapisidHash
import io.github.sudantha.youtubelite.auth.SessionStore
import io.github.sudantha.youtubelite.network.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

class ApiException(message: String) : IOException(message)

class InnerTube(private val client: OkHttpClient, private val sessions: SessionStore) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = false }
    private val bootstrapLock = Mutex()
    private var clientVersion = "2.20260918.01.00"
    private var visitorData: String? = null
    private var bootstrapped = false
    private val origin = SapisidHash.ORIGIN

    private suspend fun bootstrap() = bootstrapLock.withLock {
        if (bootstrapped) return@withLock
        // Obtain current client configuration instead of depending solely on a dated version.
        val request = Request.Builder().url("$origin/?hl=en").build()
        client.newCall(request).await().use { response ->
            if (!response.isSuccessful) throw ApiException("YouTube configuration unavailable (${response.code}). Try again.")
            val html = response.body?.byteStream()?.let { BoundedInputStream(it, 4 * 1024 * 1024).reader().readText() }.orEmpty()
            // The page embeds these as both JS objects and JSON-escaped strings; try both shapes.
            clientVersion = Regex("\"clientVersion\"\\s*:\\s*\"([0-9.]{5,40})\"").find(html)?.groupValues?.get(1)
                ?: Regex("INNERTUBE_CLIENT_VERSION[^0-9]{0,16}([0-9][0-9.]{5,40})").find(html)?.groupValues?.get(1)
                ?: clientVersion
            visitorData = Regex("\"?VISITOR_DATA\"?\\s*[:=]\\s*\\\\?\"?([A-Za-z0-9_-]{8,})").find(html)?.groupValues?.get(1)
            bootstrapped = true
        }
    }
    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun call(endpoint: String, fields: JsonObject, authenticated: Boolean = false): JsonElement = withContext(Dispatchers.IO) {
        sessions.load()
        bootstrap()
        val session = sessions.session.value
        if (authenticated && session == null) throw ApiException("Sign in to use this feature.")
        val payload = buildJsonObject {
            put("context", buildJsonObject {
                put("client", buildJsonObject {
                    put("clientName", "WEB"); put("clientVersion", clientVersion)
                    put("hl", "en"); put("gl", "US")
                    visitorData?.let { put("visitorData", it) }
                })
                put("user", buildJsonObject { put("lockedSafetyMode", false) })
            })
            fields.forEach { (key, value) -> put(key, value) }
        }
        val request = Request.Builder().url("$origin/youtubei/v1/$endpoint?prettyPrint=false")
            .header("Origin", origin).header("Referer", "$origin/")
            .header("X-Youtube-Client-Name", "1").header("X-Youtube-Client-Version", clientVersion)
            .apply {
                visitorData?.let { header("X-Goog-Visitor-Id", it) }
                session?.let {
                    header("Cookie", it.cookieHeader)
                    header("Authorization", SapisidHash.header(it.sapisid))
                    header("X-Origin", origin)
                    header("X-Goog-AuthUser", "0")
                }
            }
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        client.newCall(request).await().use { response ->
            if (response.code == 401 || response.code == 403) {
                throw ApiException("YouTube rejected this session or request. Sign in again, or try later.")
            }
            if (!response.isSuccessful) throw ApiException("YouTube returned HTTP ${response.code}. Please try again.")
            val stream = response.body?.byteStream() ?: throw ApiException("YouTube returned an empty response.")
            val root = json.decodeFromStream<JsonElement>(BoundedInputStream(stream, 8 * 1024 * 1024))
            if ((root as? JsonObject)?.containsKey("error") == true) throw ApiException("YouTube could not complete the request.")
            if (authenticated && YouTubeParser.objects(root, "responseContext").any {
                    (it["mainAppWebResponseContext"] as? JsonObject)?.get("loggedOut") == JsonPrimitive(true)
                }) throw ApiException("Your session has expired. Sign in again.")
            root
        }
    }
    suspend fun feed(feed: Feed, continuation: String? = null): VideoPage = YouTubeParser.videos(call("browse", buildJsonObject {
        if (continuation == null) put("browseId", feed.browseId) else put("continuation", continuation)
    }, feed.requiresLogin))
    suspend fun search(query: String, continuation: String? = null): VideoPage = YouTubeParser.videos(call("search", buildJsonObject {
        if (continuation == null) put("query", query) else put("continuation", continuation)
    }))
    suspend fun watch(videoId: String): WatchDetails = YouTubeParser.watch(call("next", buildJsonObject { put("videoId", videoId) }))
    suspend fun related(token: String): VideoPage = YouTubeParser.videos(call("next", buildJsonObject { put("continuation", token) }))
    suspend fun comments(token: String): CommentPage = YouTubeParser.comments(call("next", buildJsonObject { put("continuation", token) }))
    suspend fun rate(videoId: String, like: Boolean) {
        call(if (like) "like/like" else "like/dislike", buildJsonObject {
            put("target", buildJsonObject { put("videoId", videoId) })
        }, true)
    }
    suspend fun subscribe(channelId: String, subscribe: Boolean) {
        require(channelId.startsWith("UC")) { "This video's channel identifier is unavailable." }
        call("subscription/${if (subscribe) "subscribe" else "unsubscribe"}", buildJsonObject {
            put("channelIds", buildJsonArray { add(channelId) })
        }, true)
    }
    suspend fun postComment(params: String, text: String) {
        require(text.isNotBlank() && text.length <= 10000)
        call("comment/create_comment", buildJsonObject {
            put("createCommentParams", params); put("commentText", text)
        }, true)
    }
}

/**
 * Caps reads to [limit] bytes (even for chunked bodies without Content-Length) and then behaves
 * as EOF. This protects the parser from oversized/malformed responses without hanging.
 */
internal class BoundedInputStream(input: InputStream, private val limit: Int) : FilterInputStream(input) {
    private var count = 0
    override fun read(): Int {
        if (count >= limit) return -1
        val value = `in`.read()
        if (value != -1) count++
        return value
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (count >= limit) return -1
        val size = `in`.read(buffer, offset, minOf(length, limit - count))
        if (size > 0) count += size
        return size
    }
}
