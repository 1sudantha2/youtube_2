package com.ultra.youtube.app.domain

/**
 * Every model consumed by the UI layer is `@Immutable`: Compose can then skip
 * recomposition of a list item whose model instance did not change, which is what
 * keeps a 200-item feed scrolling at display refresh rate.
 */

@Immutable
data class Thumbnail(
    val url: String,
    val width: Int,
    val height: Int,
)

@Immutable
data class VideoItem(
    val id: String,
    val title: String,
    val channelName: String,
    val channelId: String,
    val durationSeconds: Int,
    val viewCountText: String,
    val publishedTimeText: String,
    val thumbnailUrl: String,
    val isLive: Boolean = false,
    val isUpcoming: Boolean = false,
    val isShort: Boolean = false,
)

/** One row of a feed. `key` is the value handed to `LazyColumn(key = ...)`. */
@Immutable
sealed interface FeedItem {
    val key: String

    @Immutable
    data class Video(val video: VideoItem) : FeedItem {
        override val key: String get() = "v:${video.id}"
    }

    @Immutable
    data class Channel(
        val channelId: String,
        val title: String,
        val avatarUrl: String,
        val subscriberCountText: String,
    ) : FeedItem {
        override val key: String get() = "c:$channelId"
    }

    @Immutable
    data class Shelf(
        val id: String,
        val title: String,
        val videos: List<VideoItem>,
    ) : FeedItem {
        override val key: String get() = "s:$id"
    }

    @Immutable
    data class Section(val id: String, val text: String) : FeedItem {
        override val key: String get() = "t:$id"
    }
}

@Immutable
data class Format(
    val itag: Int,
    val url: String?,
    val mimeType: String,
    /** `avc1.640028,mp4a.40.2` — parsed out of the raw `mimeType; codecs="…"` string. */
    val codecs: String,
    val bitrate: Int,
    val width: Int,
    val height: Int,
    val fps: Int,
    val contentLength: Long,
    val approxDurationMs: Long,
    val audioSampleRate: Int,
    val audioChannels: Int,
    val qualityLabel: String,
    /** `s` from `signatureCipher`; null when the URL is already usable. */
    val signature: String?,
    /** `sp` from `signatureCipher`, defaults to "signature". */
    val signatureParam: String,
    /** Byte range of the moov/init segment, needed to build a DASH manifest. */
    val initRangeStart: Long,
    val initRangeEnd: Long,
    /** Byte range of the sidx index, needed to build a DASH manifest. */
    val indexRangeStart: Long,
    val indexRangeEnd: Long,
) {
    val isVideo: Boolean get() = mimeType.startsWith("video/")
    val isAudio: Boolean get() = mimeType.startsWith("audio/")

    /** Human label for the quality sheet, e.g. "1080p60". */
    val label: String
        get() = qualityLabel.ifBlank {
            when {
                isAudio -> "${(bitrate / 1000)}kbps"
                height > 0 -> "${height}p"
                else -> "itag $itag"
            }
        }

    companion object {
        const val UNSET = -1L
    }
}

@Immutable
data class CaptionTrack(
    val baseUrl: String,
    val languageCode: String,
    val name: String,
    val isTranslatable: Boolean,
)

@Immutable
data class VideoDetail(
    val video: VideoItem,
    val description: String,
    val likeCountText: String,
    val viewCountText: String,
    val formats: List<Format>,
    val captions: List<CaptionTrack>,
    val related: List<VideoItem>,
    /** Opaque blobs the server expects back when performing actions on this video. */
    val likeParams: String?,
    val dislikeParams: String?,
    val subscribeParams: String?,
    /** Continuation token that opens the comments section. */
    val commentsContinuation: String?,
    /** URL of base.js, needed to descramble `signatureCipher` formats. */
    val playerJsUrl: String?,
)

@Immutable
data class Comment(
    val id: String,
    val author: String,
    val authorAvatarUrl: String,
    val text: String,
    val timeText: String,
    val likeCountText: String,
    val replyCountText: String,
    val isAuthor: Boolean,
)

@Immutable
data class CommentPage(
    val comments: List<Comment>,
    val headerText: String,
    val continuation: String?,
)

/** Result of a like / dislike / subscribe round-trip. */
@Immutable
data class ActionResult(
    val success: Boolean,
    val statusText: String,
    val toggleButtonTextId: String?,
) {
    companion object {
        val Failure = ActionResult(success = false, statusText = "", toggleButtonTextId = null)
    }
}

@Immutable
data class Account(
    val email: String,
    val visitorId: String?,
)
