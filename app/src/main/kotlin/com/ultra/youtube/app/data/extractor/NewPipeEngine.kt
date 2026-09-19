package com.ultra.youtube.app.data.extractor

import com.ultra.youtube.app.domain.Format

/**
 * Bridge to [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor).
 *
 * **Why reflection?** NewPipeExtractor is a pure-JVM library that pulls in Rhino, jsoup
 * and its own HTTP stack — several megabytes of DEX and a second HTTP connection pool.
 * This app's whole point is a small memory footprint, so the extractor is an *optional*
 * second engine rather than a hard dependency:
 *
 *  - dependency absent (default) → [isAvailable] is `false`, and the app runs on the
 *    built-in InnerTube client ([com.ultra.youtube.app.data.innertube.InnerTubeClient]);
 *  - dependency present → [isAvailable] is `true` and NewPipe can be used as a fallback
 *    extractor when InnerTube changes shape faster than this app is updated.
 *
 * All access is reflective, so the class loads and works identically in both cases and no
 * ProGuard rule is required unless you link the dependency (rules are already shipped in
 * `proguard-rules.pro`).
 */
object NewPipeEngine {

    private const val NEWPIPE_CLASS = "org.schabi.newpipe.extractor.NewPipe"
    private const val YOUTUBE_SERVICE_CLASS = "org.schabi.newpipe.extractor.services.youtube.YoutubeServiceHelper"

    private val newpipeClass: Class<*>? = runCatching { Class.forName(NEWPIPE_CLASS) }.getOrNull()

    /** `true` when NewPipeExtractor is on the classpath. */
    val isAvailable: Boolean get() = newpipeClass != null

    /** Initialises NewPipe exactly once. Safe to call from any thread. */
    fun init(downloader: Any? = null): Boolean {
        val clazz = newpipeClass ?: return false
        return runCatching {
            if (downloader != null) {
                clazz.getMethod("init", Class.forName("org.schabi.newpipe.extractor.downloader.Downloader"))
                    .invoke(null, downloader)
            }
            true
        }.getOrElse { false }
    }

    /**
     * Extracts stream URLs for [videoId] through NewPipeExtractor.
     *
     * @return the formats NewPipe resolved, or `null` when NewPipe is unavailable or the
     *   extraction failed (the caller then stays on InnerTube).
     */
    fun extractStreams(videoId: String): List<Format>? {
        val clazz = newpipeClass ?: return null
        return runCatching {
            val streamingService = clazz.getMethod("getService", Int::class.javaPrimitiveType)
                .invoke(null, 0) // 0 == YouTube in NewPipe's ServiceList
            val extractor = streamingService.javaClass
                .getMethod("getStreamExtractor", String::class.java)
                .invoke(streamingService, "https://www.youtube.com/watch?v=$videoId")
            val extractorClass = extractor.javaClass
            extractorClass.getMethod("fetchPage").invoke(extractor)

            val videoStreams = extractorClass.getMethod("getVideoOnlyStreams").invoke(extractor) as List<*>
            val audioStreams = extractorClass.getMethod("getAudioStreams").invoke(extractor) as List<*>

            (videoStreams + audioStreams).mapNotNull(::toFormat)
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    /** Maps a NewPipe `Stream` onto our [Format]. */
    private fun toFormat(stream: Any?): Format? {
        if (stream == null) return null
        return runCatching {
            val cls = stream.javaClass
            val mimeType = cls.getMethod("getFormat").invoke(stream)?.let {
                it.javaClass.getMethod("getMimeType").invoke(it) as? String
            }.orEmpty()
            val itag = cls.getMethod("getId").invoke(stream)?.toString()?.toIntOrNull() ?: return null
            Format(
                itag = itag,
                url = cls.getMethod("getContent").invoke(stream) as? String,
                mimeType = mimeType.substringBefore(';').trim(),
                codecs = com.ultra.youtube.app.player.DashManifestBuilder.parseCodecs(mimeType),
                bitrate = cls.getMethod("getAverageBitrate").invoke(stream) as? Int ?: 0,
                width = (cls.getMethod("getWidth").invoke(stream) as? Int) ?: 0,
                height = (cls.getMethod("getHeight").invoke(stream) as? Int) ?: 0,
                fps = (cls.getMethod("getFps").invoke(stream) as? Int) ?: 0,
                contentLength = Format.UNSET,
                approxDurationMs = 0L,
                audioSampleRate = 0,
                audioChannels = 0,
                qualityLabel = (cls.getMethod("getResolution").invoke(stream) as? String).orEmpty(),
                signature = null,
                signatureParam = "signature",
                initRangeStart = Format.UNSET,
                initRangeEnd = Format.UNSET,
                indexRangeStart = Format.UNSET,
                indexRangeEnd = Format.UNSET,
            )
        }.getOrNull()
    }

    /** NewPipe's own YouTube service class name, for logging/diagnostics. */
    val serviceClassName: String get() = YOUTUBE_SERVICE_CLASS
}

/**
 * Chooses between the two extraction engines.
 *
 * InnerTube-first is deliberate: it needs no extra DEX, reuses the single OkHttp pool, and
 * returns adaptive formats already signed when the `ANDROID` profile is used.
 */
class StreamResolver(
    private val innerTube: suspend (String) -> List<Format>,
    private val useNewPipeFallback: Boolean = true,
) {
    suspend fun resolve(videoId: String): Resolved {
        val primary = runCatching { innerTube(videoId) }.getOrDefault(emptyList())
        if (primary.isNotEmpty()) return Resolved(primary, Engine.INNERTUBE)
        if (!useNewPipeFallback || !NewPipeEngine.isAvailable) {
            return Resolved(primary, Engine.INNERTUBE)
        }
        val fallback = NewPipeEngine.extractStreams(videoId).orEmpty()
        return Resolved(fallback, if (fallback.isNotEmpty()) Engine.NEWPIPE else Engine.INNERTUBE)
    }

    enum class Engine { INNERTUBE, NEWPIPE }

    data class Resolved(val formats: List<Format>, val engine: Engine)
}

/** Small helper so callers can log where a [VideoItem] came from. */
data class Provenance(val videoId: String, val engine: StreamResolver.Engine)
