package com.ultra.youtube.app.data.innertube

import com.ultra.youtube.app.data.auth.CookieStore
import com.ultra.youtube.app.data.auth.WebViewCookieExtractor
import com.ultra.youtube.app.data.innertube.JsonExt.arr
import com.ultra.youtube.app.data.innertube.JsonExt.bool
import com.ultra.youtube.app.data.innertube.JsonExt.jsonObj
import com.ultra.youtube.app.data.innertube.JsonExt.num
import com.ultra.youtube.app.data.innertube.JsonExt.str
import com.ultra.youtube.app.data.innertube.JsonExt.with
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Client-side InnerTube transport. No backend, no API key of our own: the app speaks the
 * same private JSON protocol the official clients use, straight from the device.
 *
 * Two client profiles are available because endpoints behave differently:
 *  - [ANDROID] — adaptive formats arrive pre-signed (no `signatureCipher`, so no base.js
 *    descrambling), which is the cheapest path for the player.
 *  - [WEB] — richest feed/metadata payloads; formats need descrambling through
 *    [SigCipher].
 *
 * Every authenticated call derives the `SAPISIDHASH` header on-device from the WebView
 * cookies ([com.ultra.youtube.app.data.auth.CookieStore]), so subscriptions, library,
 * likes and comments work without OAuth tokens or a server.
 */
class InnerTubeClient(
    private val http: OkHttpClient,
    private val auth: AuthProvider? = null,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    fun interface AuthProvider {
        fun session(): WebViewCookieExtractor.SessionCookies?
    }

    class InnertubeException(
        message: String,
        val httpCode: Int? = null,
    ) : IOException(message)

    data class ClientProfile(
        val clientName: String,
        val clientVersion: String,
        val platform: String,
        val androidSdkVersion: Int? = null,
        val osName: String? = null,
        val osVersion: String? = null,
        val userAgent: String,
    )

    companion object {
        const val BASE_URL = "https://www.youtube.com/youtubei/v1"

        /** Public InnerTube web key; identical for every client of the same profile. */
        const val API_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"

        val ANDROID = ClientProfile(
            clientName = "ANDROID",
            clientVersion = "19.29.37",
            platform = "MOBILE",
            androidSdkVersion = 34,
            osName = "Android",
            osVersion = "14",
            userAgent = "com.google.android.youtube/19.29.37 (Linux; U; Android 14) gzip",
        )

        val WEB = ClientProfile(
            clientName = "WEB",
            clientVersion = "2.20240701.00.00",
            platform = "DESKTOP",
            osName = "Windows",
            osVersion = "10.0",
            userAgent = WebViewCookieExtractor.DESKTOP_USER_AGENT,
        )

        val TVHTML5 = ClientProfile(
            clientName = "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
            clientVersion = "2.0",
            platform = "TV",
            userAgent = WebViewCookieExtractor.DESKTOP_USER_AGENT,
        )

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            // One shared pool: sockets and their buffers are a hidden memory cost.
            .connectionPool(ConnectionPool(5, 30, TimeUnit.SECONDS))
            .build()
    }

    // ---------------------------------------------------------------- endpoints

    suspend fun browse(
        browseId: String,
        params: String? = null,
        continuation: String? = null,
        client: ClientProfile = ANDROID,
    ): JsonObject = post(
        "browse",
        jsonObj(
            "browseId" to str(if (continuation == null) browseId else null),
            "params" to str(if (continuation == null) params else null),
            "continuation" to str(continuation),
            "continuationCommand" to continuation?.let {
                jsonObj(
                    "token" to str(it),
                    "request" to str("CONTINUATION_REQUEST_TYPE_BROWSE"),
                )
            },
        ),
        client,
    )

    suspend fun search(
        query: String,
        params: String? = null,
        continuation: String? = null,
        client: ClientProfile = ANDROID,
    ): JsonObject = post(
        "search",
        jsonObj(
            "query" to str(if (continuation == null) query else null),
            "params" to str(if (continuation == null) params else null),
            "continuation" to str(continuation),
        ),
        client,
    )

    /** Watch page payload: player response, metadata, engagement panels, related list. */
    suspend fun next(
        videoId: String,
        playlistId: String? = null,
        client: ClientProfile = ANDROID,
    ): JsonObject = post(
        "next",
        jsonObj("videoId" to str(videoId), "playlistId" to str(playlistId)),
        client,
    )

    /** Follows any continuation token (comment pages, feed paging, shelves). */
    suspend fun continuation(token: String, client: ClientProfile = ANDROID): JsonObject =
        post("next", jsonObj("continuation" to str(token)), client)

    // ---------------------------------------------------------------- actions

    suspend fun like(videoId: String, params: String?): JsonObject =
        post("like/like", likeTarget(videoId, params), ANDROID, requireAuth = true)

    suspend fun dislike(videoId: String, params: String?): JsonObject =
        post("like/dislike", likeTarget(videoId, params), ANDROID, requireAuth = true)

    suspend fun removeLike(videoId: String, params: String?): JsonObject =
        post("like/removelike", likeTarget(videoId, params), ANDROID, requireAuth = true)

    suspend fun subscribe(channelId: String, params: String?): JsonObject =
        post(
            "subscription/subscribe",
            subscriptionBody(channelId, params),
            ANDROID,
            requireAuth = true,
        )

    suspend fun unsubscribe(channelId: String, params: String?): JsonObject =
        post(
            "subscription/unsubscribe",
            subscriptionBody(channelId, params),
            ANDROID,
            requireAuth = true,
        )

    suspend fun createComment(videoId: String, content: String): JsonObject = post(
        "next/create_comment",
        jsonObj(
            "comment" to jsonObj("simpleText" to str(content)),
            "createCommentParams" to str(CommentParams.build(videoId)),
            "botguardData" to jsonObj(
                "program" to str(""),
                "interpreterSafeUrl" to jsonObj(
                    "privateDoNotAccessOrElseTrustedResourceUrlWrappedValue" to str(""),
                ),
            ),
        ),
        ANDROID,
        requireAuth = true,
    )

    private fun likeTarget(videoId: String, params: String?): JsonObject = jsonObj(
        "target" to jsonObj("videoId" to str(videoId)),
        "params" to str(params),
    )

    private fun subscriptionBody(channelId: String, params: String?): JsonObject = jsonObj(
        "channelIds" to arr(str(channelId)),
        "params" to str(params),
        "prettyPrint" to bool(false),
    )

    // ---------------------------------------------------------------- transport

    suspend fun post(
        endpoint: String,
        body: JsonObject,
        client: ClientProfile = ANDROID,
        requireAuth: Boolean = false,
        extraHeaders: Map<String, String> = emptyMap(),
    ): JsonObject = withContext(dispatcher) {
        val session = auth?.session()
        if (requireAuth && session?.isAuthenticated != true) {
            throw InnertubeException("Sign in required for $endpoint")
        }

        val request = Request.Builder()
            .url("$BASE_URL/$endpoint?key=$API_KEY&prettyPrint=false")
            .post(
                JsonExt.json
                    .encodeToString(JsonObject.serializer(), body.with("context", context(client)))
                    .toRequestBody(JSON_MEDIA_TYPE),
            )
            .header("User-Agent", client.userAgent)
            .header("Content-Type", "application/json")
            .header("Origin", WebViewCookieExtractor.YOUTUBE_ORIGIN)
            .header("X-Origin", WebViewCookieExtractor.YOUTUBE_ORIGIN)
            .header("Accept-Language", "en-US,en;q=0.9")
            .apply {
                val visitor = session?.cookies?.get("VISITOR_INFO1_LIVE")
                if (!visitor.isNullOrBlank()) header("X-Goog-Visitor-Id", visitor)
                val sapisid = session?.sapishid
                if (!sapisid.isNullOrBlank()) {
                    SapishHash.header(sapisid, System.currentTimeMillis())
                        ?.let { header("Authorization", it) }
                    session.header.let { header("Cookie", it) }
                }
                extraHeaders.forEach { (name, value) -> header(name, value) }
            }
            .build()

        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw InnertubeException(
                    "InnerTube $endpoint failed: ${response.code} ${response.message}",
                    response.code,
                )
            }
            val text = response.body?.string().orEmpty()
            JsonExt.json.parseToJsonElement(text) as? JsonObject
                ?: throw InnertubeException("InnerTube $endpoint returned a non-object body")
        }
    }

    private fun context(client: ClientProfile): JsonObject = jsonObj(
        "client" to jsonObj(
            "clientName" to str(client.clientName),
            "clientVersion" to str(client.clientVersion),
            "platform" to str(client.platform),
            "hl" to str("en"),
            "gl" to str("US"),
            "androidSdkVersion" to client.androidSdkVersion?.let { num(it) },
            "osName" to str(client.osName),
            "osVersion" to str(client.osVersion),
            "userAgent" to str(client.userAgent),
        ),
        "user" to jsonObj(
            "lockedSafetyMode" to bool(false),
            "enableSafetyMode" to bool(false),
        ),
        "request" to jsonObj(
            "useSsl" to bool(true),
            "internalExperimentFlags" to arr(),
        ),
    )

    /** Convenience factory: build a client bound to the app-wide [CookieStore]. */
    fun withCookieStore(store: CookieStore): InnerTubeClient =
        InnerTubeClient(http, AuthProvider { store.session.value }, dispatcher)

    /** Exposed for tests. */
    internal fun parse(body: String): JsonElement = JsonExt.json.parseToJsonElement(body)
}

/**
 * Builds the `createCommentParams` blob for `youtubei/v1/next/create_comment`.
 *
 * The value is a length-delimited protobuf whose only variable part is the video id:
 * field 2 (wire tag `0x12`) holding the id's UTF-8 bytes, base64url-encoded without
 * padding. Pure function, no Android dependency, so it is directly unit-testable.
 */
object CommentParams {

    /** Wire tag for a length-delimited field number 2. */
    const val FIELD_TAG: Byte = 0x12

    fun build(videoId: String, encode: (ByteArray) -> String = ::base64UrlNoPadding): String {
        val id = videoId.toByteArray(Charsets.UTF_8)
        return encode(byteArrayOf(FIELD_TAG, id.size.toByte()) + id)
    }

    fun base64UrlNoPadding(bytes: ByteArray): String =
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
