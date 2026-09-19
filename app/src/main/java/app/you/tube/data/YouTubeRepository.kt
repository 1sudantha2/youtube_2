package app.you.tube.data

import app.you.tube.core.extract.StreamRegistry
import app.you.tube.core.extract.StreamRepository
import app.you.tube.core.model.ChannelItem
import app.you.tube.core.model.CommentItem
import app.you.tube.core.model.CommentsPage
import app.you.tube.core.network.LikeAction
import app.you.tube.core.model.VideoItem
import app.you.tube.core.model.WatchNextInfo
import app.you.tube.core.network.InnerTubeClient
import app.you.tube.core.network.InnerTubeParser

/**
 * Single facade over the InnerTube data layer + the NewPipe extraction engine.
 * Feed pages return `(videos, continuationToken)` pairs; every call is a
 * suspend function executed off the main thread.
 */
class YouTubeRepository(
    private val innerTube: InnerTubeClient,
    private val streams: StreamRepository
) {

    // ------------------------------------------------------------- feeds

    suspend fun homeFeed(continuation: String?): Pair<List<VideoItem>, String?> =
        InnerTubeParser.parseFeed(innerTube.browse(browseId = BROWSE_HOME, continuation = continuation))

    suspend fun subscriptionsFeed(continuation: String?): Pair<List<VideoItem>, String?> =
        InnerTubeParser.parseFeed(innerTube.browse(browseId = BROWSE_SUBSCRIPTIONS, continuation = continuation))

    suspend fun historyFeed(continuation: String?): Pair<List<VideoItem>, String?> =
        InnerTubeParser.parseFeed(innerTube.browse(browseId = BROWSE_HISTORY, continuation = continuation))

    /** FEhistory or a playlist id (VLLM / VLWL) — same response family, same parser. */
    suspend fun playlistOrHistory(browseId: String, continuation: String?): Pair<List<VideoItem>, String?> =
        InnerTubeParser.parseFeed(innerTube.browse(browseId = browseId, continuation = continuation))

    suspend fun playlistFeed(playlistId: String, continuation: String?): Pair<List<VideoItem>, String?> =
        InnerTubeParser.parseFeed(innerTube.browse(browseId = playlistId, continuation = continuation))

    // ------------------------------------------------------------- search

    suspend fun search(query: String, continuation: String?): Triple<List<VideoItem>, List<ChannelItem>, String?> =
        InnerTubeParser.parseSearch(innerTube.search(query, continuation))

    suspend fun suggestions(query: String): List<String> = innerTube.suggestions(query)

    // --------------------------------------------------------- watch page

    suspend fun watchNext(videoId: String): WatchNextInfo =
        InnerTubeParser.parseWatchNext(innerTube.next(videoId))

    suspend fun comments(continuationToken: String): CommentsPage =
        InnerTubeParser.parseComments(innerTube.browse(continuation = continuationToken))

    // -------------------------------------------------------- engagement

    suspend fun likeVideo(videoId: String, action: LikeAction): Boolean =
        innerTube.like(videoId, action)

    suspend fun subscribeChannel(channelId: String, subscribe: Boolean): Boolean =
        innerTube.subscribe(channelId, subscribe)

    suspend fun postComment(createCommentParams: String, text: String): Boolean =
        innerTube.createComment(createCommentParams, text)

    suspend fun commentAction(actionToken: String): Boolean =
        innerTube.performCommentAction(actionToken)

    suspend fun commentReply(createReplyParams: String, text: String): Boolean =
        innerTube.createCommentReply(createReplyParams, text)

    // ----------------------------------------------------------- streams

    suspend fun extractStreams(videoId: String) = streams.extract(videoId)

    fun registerBundle(bundle: app.you.tube.core.model.StreamBundle) = StreamRegistry.put(bundle)

    companion object {
        const val BROWSE_HOME = "FEwhat_to_watch"
        const val BROWSE_SUBSCRIPTIONS = "FEsubscriptions"
        const val BROWSE_HISTORY = "FEhistory"
        const val PLAYLIST_LIKED = "VLLM"
        const val PLAYLIST_WATCH_LATER = "VLWL"
    }
}
