package io.github.sudantha.youtubelite.data

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf

@Immutable
data class Video(
    val id: String, val title: String, val thumbnail: String,
    val channel: String = "", val channelId: String = "", val duration: String = "",
    val views: String = "",
)
@Immutable
data class Comment(val id: String, val author: String, val text: String, val likes: String = "")
@Immutable
data class VideoPage(val videos: PersistentList<Video> = persistentListOf(), val continuation: String? = null)
@Immutable
data class CommentPage(val comments: PersistentList<Comment> = persistentListOf(), val continuation: String? = null, val createCommentParams: String? = null)
@Immutable
data class WatchDetails(
    val recommendations: VideoPage = VideoPage(), val commentsToken: String? = null,
    val createCommentParams: String? = null, val subscribed: Boolean? = null,
)
@Immutable
enum class Feed(val label: String, val browseId: String, val requiresLogin: Boolean = false) {
    Home("Home", "FEwhat_to_watch"), Subscriptions("Subscriptions", "FEsubscriptions", true),
    Library("Library", "FElibrary", true), Liked("Liked", "VLLM", true),
}
@Immutable
data class FeedState(
    val feed: Feed = Feed.Home, val query: String = "", val videos: PersistentList<Video> = persistentListOf(),
    val loading: Boolean = true, val loadingMore: Boolean = false, val continuation: String? = null,
    val error: String? = null, val signedIn: Boolean = false,
)
