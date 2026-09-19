package app.you.tube.core.model

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/**
 * All UI models are [Immutable] with only val properties -> the Compose compiler
 * can skip recomposition for any subtree that receives an unchanged instance.
 */

@Immutable
data class VideoItem(
    val id: String,
    val title: String,
    val channelName: String,
    val channelId: String,
    val thumbnailUrl: String,
    val duration: String? = null,
    val views: String? = null,
    val published: String? = null,
    val isLive: Boolean = false,
    val isShort: Boolean = false
)

@Immutable
data class ChannelItem(
    val id: String,
    val name: String,
    val avatarUrl: String,
    val subscribers: String? = null
)

enum class LikeState { NONE, LIKE, DISLIKE }

@Immutable
data class CommentItem(
    val id: String,
    val authorName: String,
    val authorId: String? = null,
    val authorHandle: String? = null,
    val avatarUrl: String? = null,
    val text: String,
    val publishedTime: String? = null,
    val likeCount: String? = null,
    val replyCount: Int = 0,
    val isLiked: Boolean = false,
    val isHearted: Boolean = false,
    val isCreator: Boolean = false,
    val repliesToken: String? = null,
    val likeActionToken: String? = null,
    val unlikeActionToken: String? = null
)

@Immutable
data class CommentSortOption(
    val title: String,
    val token: String,
    val selected: Boolean = false
)

@Immutable
data class CommentsPage(
    val items: ImmutableList<CommentItem> = persistentListOf(),
    val continuation: String? = null,
    val createCommentParams: String? = null,
    val sorts: ImmutableList<CommentSortOption> = persistentListOf()
)

@Immutable
data class WatchNextInfo(
    val commentsToken: String? = null,
    val isSubscribed: Boolean = false
)

/** One selectable stream: a video-only itag (played merged with audio) or a muxed progressive stream. */
@Immutable
data class StreamQuality(
    val itag: Int,
    val label: String,
    val url: String,
    val isMuxed: Boolean,
    val container: String,
    val height: Int,
    val fps: Int
)

@Immutable
data class StreamBundle(
    val videoId: String,
    val title: String,
    val channelId: String,
    val channelName: String,
    val channelAvatarUrl: String? = null,
    val subscriberCount: String? = null,
    val viewCountText: String? = null,
    val likeCountText: String? = null,
    val uploadDate: String? = null,
    val description: String? = null,
    val durationText: String? = null,
    val isLive: Boolean = false,
    val hlsUrl: String? = null,
    val qualities: ImmutableList<StreamQuality> = persistentListOf(),
    val bestAudioItag: Int? = null,
    val bestAudioUrl: String? = null,
    val fallbackMuxed: StreamQuality? = null,
    val related: ImmutableList<VideoItem> = persistentListOf()
) {
    fun qualityByItag(itag: Int?): StreamQuality? = qualities.firstOrNull { it.itag == itag }
}

@Immutable
data class FeedUiState(
    val items: ImmutableList<VideoItem> = persistentListOf(),
    val isLoadingInitial: Boolean = false,
    val isLoadingMore: Boolean = false,
    val continuation: String? = null,
    val error: String? = null,
    val requireAuth: Boolean = false
)

@Immutable
data class SearchUiState(
    val query: String = "",
    val suggestions: ImmutableList<String> = persistentListOf(),
    val results: ImmutableList<VideoItem> = persistentListOf(),
    val channels: ImmutableList<ChannelItem> = persistentListOf(),
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val continuation: String? = null,
    val searched: Boolean = false,
    val error: String? = null
)
