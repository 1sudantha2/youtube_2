package app.you.tube.core.extract

import app.you.tube.core.model.StreamBundle
import app.you.tube.core.model.StreamQuality
import app.you.tube.core.model.VideoItem
import app.you.tube.core.util.Formats
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamExtractor
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Process-wide registry of the most recently extracted stream bundles.
 *
 * The player UI runs on a MediaController while the player + media source
 * factory live inside the MediaSessionService — both in this same process.
 * When the controller sets a `ytmerge://` MediaItem, the service-side factory
 * resolves itag -> URL mappings from this registry, keeping IPC payloads tiny
 * (a custom URI instead of raw CDN URLs) and quality switching instant.
 */
object StreamRegistry {
    private const val MAX_ENTRIES = 6
    private val lock = ReentrantLock()
    private val map = LinkedHashMap<String, StreamBundle>(16, 0.75f, true)

    fun put(bundle: StreamBundle) = lock.withLock {
        map[bundle.videoId] = bundle
        while (map.size > MAX_ENTRIES) {
            map.remove(map.keys.first())
        }
    }

    fun get(videoId: String): StreamBundle? = lock.withLock { map[videoId] }

    fun clear() = lock.withLock { map.clear() }
}

/**
 * Stream + metadata extraction via NewPipeExtractor (signature & n-param
 * deciphering handled internally by the library). All blocking work runs on
 * [Dispatchers.IO]; results are mapped to lean immutable models.
 */
class StreamRepository(private val okHttpClient: OkHttpClient) {

    suspend fun extract(videoId: String): Result<StreamBundle> = withContext(Dispatchers.IO) {
        runCatching {
            val extractor = ServiceList.YouTube
                .getStreamExtractor("https://www.youtube.com/watch?v=$videoId")
            extractor.fetchPage()
            buildBundle(videoId, extractor)
        }
    }

    private fun buildBundle(videoId: String, e: StreamExtractor): StreamBundle {
        val progressiveVideo = e.videoStreams.filter {
            it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && !it.url.isNullOrBlank()
        }
        val videoOnly = progressiveVideo.filter { it.isVideoOnly }
        val muxed = progressiveVideo.filter { !it.isVideoOnly }

        // One entry per resolution, highest-bitrate variant wins.
        val qualities = videoOnly
            .groupBy { it.height }
            .map { (_, group) -> group.maxBy { it.bitrate } }
            .sortedByDescending { it.height }
            .map { it.toQuality() }

        // Audio: hardware-decoded AAC (M4A) beats Opus on budget devices —
        // software Opus decode burns CPU cycles. Prefer M4A, then highest bitrate.
        val audio = e.audioStreams
            .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && !it.url.isNullOrBlank() }
            .sortedWith(
                compareByDescending<org.schabi.newpipe.extractor.stream.AudioStream> { it.format == MediaFormat.M4A }
                    .thenByDescending { it.averageBitrate }
            )
            .firstOrNull()

        val fallback = muxed
            .sortedWith(compareByDescending<VideoStream> { it.height <= 720 }.thenByDescending { it.height })
            .firstOrNull()
            ?.toQuality()

        val related = (e.relatedItems?.items ?: emptyList())
            .filterIsInstance<StreamInfoItem>()
            .mapNotNull { item ->
                val id = Formats.videoIdFromUrl(item.url) ?: return@mapNotNull null
                VideoItem(
                    id = id,
                    title = item.name ?: "",
                    channelName = item.uploaderName ?: "",
                    channelId = Formats.channelIdFromUrl(item.uploaderUrl) ?: "",
                    thumbnailUrl = item.thumbnails.maxByOrNull { t -> t.height * t.width }?.url
                        ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg",
                    duration = Formats.duration(item.duration),
                    views = item.viewCount.takeIf { it >= 0 }?.let { v -> Formats.count(v) + " views" },
                    published = item.textualUploadDate
                )
            }

        val likeCount = e.likeCount
        val viewCount = e.viewCount
        val subs = e.uploaderSubscriberCount
        val avatar = e.uploaderAvatars.maxByOrNull { it.height * it.width }?.url

        return StreamBundle(
            videoId = videoId,
            title = e.name ?: "",
            channelId = Formats.channelIdFromUrl(e.uploaderUrl) ?: "",
            channelName = e.uploaderName ?: "",
            channelAvatarUrl = avatar,
            subscriberCount = subs.takeIf { it >= 0 }?.let { Formats.count(it) + " subscribers" },
            viewCountText = viewCount.takeIf { it >= 0 }?.let { Formats.count(it) + " views" },
            likeCountText = likeCount.takeIf { it >= 0 }?.let { Formats.count(it) },
            uploadDate = e.textualUploadDate,
            description = e.description?.content,
            durationText = Formats.duration(e.lengthSeconds),
            isLive = e.isLive,
            hlsUrl = e.hlsUrl?.takeIf { it.isNotBlank() },
            qualities = qualities.toImmutableList(),
            bestAudioItag = audio?.itag,
            bestAudioUrl = audio?.url,
            fallbackMuxed = fallback,
            related = related.toImmutableList()
        )
    }

    private fun VideoStream.toQuality() = StreamQuality(
        itag = itag,
        label = buildString {
            append(height)
            append("p")
            if (fps > 30) append(fps)
        },
        url = url.orEmpty(),
        isMuxed = !isVideoOnly,
        container = format?.name?.lowercase() ?: "",
        height = height,
        fps = fps
    )
}
