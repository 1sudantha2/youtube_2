package com.youtubelite.app.extractor

import com.youtubelite.app.model.QualityOption
import com.youtubelite.app.model.StreamSet
import com.youtubelite.app.model.Video
import com.youtubelite.app.model.WatchMeta
import com.youtubelite.app.util.formatSeconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.limitedParallelism
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.InfoType
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.text.NumberFormat
import java.util.Locale

data class Resolved(
    val meta: WatchMeta,
    val streams: StreamSet,
    val related: List<Video>,
)

/**
 * Turns a video id into playback-ready models via NewPipeExtractor.
 * Extraction (player JS + Rhino deciphering) is CPU-heavy and not re-entrant,
 * so it runs serialized on a single IO lane: consistent latency, no thundering
 * herd on budget SoCs.
 */
object StreamResolver {

    private val extractionLane = Dispatchers.IO.limitedParallelism(1)
    private val videoIdRegex = Regex("(?:v=|/shorts/|youtu\\.be/)([A-Za-z0-9_-]{11})")
    private val numberFormat = NumberFormat.getIntegerInstance(Locale.US)

    suspend fun resolve(videoId: String): Resolved = withContext(extractionLane) {
        val info = StreamInfo.getInfo("https://www.youtube.com/watch?v=$videoId")

        val qualities = info.videoOnlyStreams.asSequence()
            .filter { it.isUrl }
            .mapNotNull { s ->
                val height = s.resolution?.filter(Char::isDigit)?.toIntOrNull() ?: return@mapNotNull null
                QualityOption(
                    label = s.resolution ?: "${height}p",
                    height = height,
                    url = s.content,
                    fps = if (s.resolution?.endsWith("60") == true) 60 else 30,
                )
            }
            .distinctBy { it.height to it.fps }
            .sortedBy { it.height }
            .toList()

        val muxed = info.videoStreams.asSequence()
            .filter { !it.isVideoOnly && it.isUrl }
            .mapNotNull { s ->
                val height = s.resolution?.filter(Char::isDigit)?.toIntOrNull() ?: return@mapNotNull null
                QualityOption(
                    label = "${height}p",
                    height = height,
                    url = s.content,
                    fps = if (s.resolution?.endsWith("60") == true) 60 else 30,
                )
            }
            .distinctBy { it.height }
            .sortedBy { it.height }
            .toList()

        // Prefer Opus (better SNR per bit, cheap to decode) at the top usable bitrate.
        val audio = info.audioStreams
            .filter { it.isUrl }
            .maxByOrNull { it.averageBitrate * 2 + if (it.format == MediaFormat.WEBMA_OPUS) 1 else 0 }

        val channelId = info.uploaderUrl?.substringAfter("/channel/", "")?.takeIf { it.length == 24 }

        val meta = WatchMeta(
            videoId = videoId,
            title = info.name.orEmpty(),
            channel = info.uploaderName.orEmpty(),
            channelId = channelId.orEmpty(),
            thumb = info.thumbnails.lastOrNull()?.url
                ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
            views = info.viewCount.takeIf { it >= 0 }?.let { "${numberFormat.format(it)} views" }.orEmpty(),
            published = info.textualUploadDate.orEmpty(),
            likes = info.likeCount.takeIf { it > 0 }?.let { shortCount(it) }.orEmpty(),
            description = info.description?.content.orEmpty(),
        )

        Resolved(
            meta = meta,
            streams = StreamSet(
                qualities = qualities,
                audioUrl = audio?.content,
                muxed = muxed,
            ),
            related = info.relatedItems
                .filter { it.infoType == InfoType.VIDEO }
                .mapNotNull { item ->
                    val streamItem = item as? StreamInfoItem ?: return@mapNotNull null
                    val id = videoIdRegex.find(streamItem.url ?: "")
                        ?.groupValues?.get(1) ?: return@mapNotNull null
                    Video(
                        id = id,
                        title = streamItem.name.orEmpty(),
                        channel = streamItem.uploaderName.orEmpty(),
                        channelId = "",
                        views = streamItem.viewCount.takeIf { it >= 0 }
                            ?.let { "${numberFormat.format(it)} views" }.orEmpty(),
                        published = streamItem.textualUploadDate.orEmpty(),
                        duration = formatSeconds(streamItem.duration),
                        thumb = streamItem.thumbnails.lastOrNull()?.url
                            ?: "https://i.ytimg.com/vi/$id/mqdefault.jpg",
                    )
                }
                .distinctBy { it.id },
        )
    }

    private fun shortCount(n: Long): String = when {
        n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
        n >= 1_000 -> "%.1fK".format(n / 1_000.0)
        else -> n.toString()
    }
}
