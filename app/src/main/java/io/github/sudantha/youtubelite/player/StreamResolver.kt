package io.github.sudantha.youtubelite.player

import androidx.compose.runtime.Immutable
import io.github.sudantha.youtubelite.data.YouTubeParser
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.IOException

@Immutable
data class StreamOption(val id: String, val url: String, val height: Int, val videoOnly: Boolean, val mime: String)
@Immutable
data class ResolvedStreams(val videos: PersistentList<StreamOption>, val audio: String?, val manifest: String?)

class StreamResolver(private val client: OkHttpClient) {
    // NewPipe has process-global caches; serialize extraction and don't retain StreamInfo/JS trees.
    private val lock = Mutex()
    private var initialized = false
    suspend fun resolve(videoId: String): ResolvedStreams = lock.withLock {
        require(YouTubeParser.validVideoId(videoId)) { "Invalid video identifier" }
        runInterruptible(Dispatchers.IO) {
            if (!initialized) { NewPipe.init(ExtractorDownloader(client)); initialized = true }
            val info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$videoId")
            val videos = (info.videoStreams + info.videoOnlyStreams)
                .filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
                .map { StreamOption(it.id, it.content, it.resolution.takeWhile(Char::isDigit).toIntOrNull() ?: it.height,
                    it.isVideoOnly, it.format?.mimeType.orEmpty()) }
                .filter { it.height in 144..1080 && it.url.startsWith("https://") }
                .sortedWith(compareBy<StreamOption> { it.height }.thenBy { it.mime != "video/mp4" })
                .distinctBy { it.height to it.videoOnly }.toPersistentList()
            val audio = info.audioStreams.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
                .sortedWith(compareBy<org.schabi.newpipe.extractor.stream.AudioStream> { it.format?.mimeType != "audio/mp4" }
                    .thenBy { it.averageBitrate }).firstOrNull()?.content
            val manifest = info.hlsUrl.takeIf { it.startsWith("https://") }
                ?: info.dashMpdUrl.takeIf { it.startsWith("https://") }
            check(videos.isNotEmpty() || manifest != null) { "No supported streams. Live/OTF or restricted videos may be unavailable." }
            ResolvedStreams(videos, audio, manifest)
        }
    }
}

private class ExtractorDownloader(private val client: OkHttpClient) : Downloader() {
    override fun execute(request: Request): Response {
        val body = request.dataToSend()?.toRequestBody(null)
        val http = okhttp3.Request.Builder().url(request.url())
            .method(request.httpMethod(), body)
            .apply { request.headers().forEach { (name, values) -> values.forEach { addHeader(name, it) } } }
            .build()
        client.newCall(http).execute().use { response ->
            if (response.code == 429) throw IOException("YouTube rate limited extraction. Please try later.")
            // Extraction needs text, but never read arbitrarily large error/challenge bodies.
            val text = response.body?.byteStream()?.let {
                io.github.sudantha.youtubelite.data.BoundedInputStream(it, 8 * 1024 * 1024).reader().readText()
            }.orEmpty()
            return Response(response.code, response.message, response.headers.toMultimap(), text, response.request.url.toString())
        }
    }
}
