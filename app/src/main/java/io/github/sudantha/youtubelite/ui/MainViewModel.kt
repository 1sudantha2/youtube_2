package io.github.sudantha.youtubelite.ui

import android.app.Application
import android.content.ComponentName
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.compose.runtime.Immutable
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import io.github.sudantha.youtubelite.YouTubeApp
import io.github.sudantha.youtubelite.data.*
import io.github.sudantha.youtubelite.player.PlaybackService
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Immutable
data class WatchState(
    val video: Video? = null, val loading: Boolean = false,
    val related: PersistentList<Video> = persistentListOf(), val relatedToken: String? = null,
    val comments: PersistentList<Comment> = persistentListOf(), val commentsToken: String? = null,
    val initialCommentsToken: String? = null, val commentParams: String? = null,
    val commentsLoading: Boolean = false, val relatedLoading: Boolean = false,
    val subscribed: Boolean? = null, val actionPending: Boolean = false, val message: String? = null,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as YouTubeApp
    val engine = app.engine
    val playback = engine.state
    private val mutableFeed = MutableStateFlow(FeedState())
    val feed = mutableFeed.asStateFlow()
    private val mutableWatch = MutableStateFlow(WatchState())
    val watch = mutableWatch.asStateFlow()
    val backgroundAudio = app.preferences.backgroundAudio.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    private var loadJob: Job? = null
    private var watchJob: Job? = null
    private var commentsJob: Job? = null
    private var relatedJob: Job? = null
    private var actionJob: Job? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var foreground = true

    init {
        viewModelScope.launch {
            try { app.sessions.load() } catch (_: Exception) {
                mutableFeed.update { it.copy(error = "Secure storage unavailable. Restart the app; if necessary clear app data.", loading = false) }
                return@launch
            }
            refresh()
            app.sessions.session.collect { session -> mutableFeed.update { it.copy(signedIn = session != null) } }
        }
    }
    fun selectFeed(feed: Feed) {
        mutableFeed.value = FeedState(feed = feed, signedIn = app.sessions.session.value != null)
        refresh()
    }
    fun search(query: String) {
        mutableFeed.value = FeedState(query = query.trim(), signedIn = app.sessions.session.value != null)
        refresh()
    }
    fun refresh() {
        loadJob?.cancel()
        val snapshot = mutableFeed.value
        mutableFeed.value = snapshot.copy(loading = true, loadingMore = false, videos = persistentListOf(), continuation = null, error = null)
        loadJob = viewModelScope.launch {
            try {
                val page = if (snapshot.query.isNotBlank()) app.api.search(snapshot.query) else app.api.feed(snapshot.feed)
                mutableFeed.update { it.copy(loading = false, videos = page.videos, continuation = page.continuation) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableFeed.update { it.copy(loading = false, error = readable(error)) }
            }
        }
    }
    fun loadMore() {
        val snapshot = mutableFeed.value
        val token = snapshot.continuation ?: return
        if (snapshot.loading || snapshot.loadingMore) return
        mutableFeed.update { it.copy(loadingMore = true, error = null) }
        loadJob = viewModelScope.launch {
            try {
                val page = if (snapshot.query.isNotBlank()) app.api.search(snapshot.query, token) else app.api.feed(snapshot.feed, token)
                mutableFeed.update { it.copy(loadingMore = false,
                    videos = (it.videos + page.videos).distinctBy(Video::id).takeLast(240).toPersistentList(),
                    continuation = page.continuation?.takeUnless { next -> next == token }) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableFeed.update { it.copy(loadingMore = false, error = readable(error)) }
            }
        }
    }
    private suspend fun connect() {
        if (controller?.isConnected == true) return
        controllerFuture?.let(MediaController::releaseFuture)
        val future = MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync()
        controllerFuture = future
        controller = suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { MediaController.releaseFuture(future) }
            future.addListener({
                if (continuation.isActive) {
                    try { continuation.resume(future.get()) }
                    catch (error: Exception) { continuation.resumeWithException(error) }
                }
            }, ContextCompat.getMainExecutor(app))
        }
    }
    fun open(video: Video) {
        watchJob?.cancel(); commentsJob?.cancel(); relatedJob?.cancel(); actionJob?.cancel()
        mutableWatch.value = WatchState(video = video, loading = true)
        watchJob = viewModelScope.launch {
            try {
                connect()
                engine.visibility(foreground, backgroundAudio.value)
                engine.play(video)
                val details = app.api.watch(video.id)
                mutableWatch.update { it.copy(loading = false, related = details.recommendations.videos,
                    relatedToken = details.recommendations.continuation, commentsToken = details.commentsToken,
                    initialCommentsToken = details.commentsToken, commentParams = details.createCommentParams,
                    subscribed = details.subscribed) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableWatch.update { it.copy(loading = false, message = readable(error)) }
            }
        }
    }
    fun retryPlayback() { mutableWatch.value.video?.let(::open) }
    fun closeVideo() {
        watchJob?.cancel(); commentsJob?.cancel(); relatedJob?.cancel(); actionJob?.cancel()
        engine.stop(); mutableWatch.value = WatchState()
    }
    fun loadComments() {
        val snapshot = mutableWatch.value
        val token = snapshot.commentsToken ?: return
        if (snapshot.commentsLoading) return
        mutableWatch.update { it.copy(commentsLoading = true, message = null) }
        commentsJob = viewModelScope.launch {
            try {
                val page = app.api.comments(token)
                mutableWatch.update { it.copy(commentsLoading = false,
                    comments = (it.comments + page.comments).distinctBy(Comment::id).takeLast(200).toPersistentList(),
                    commentsToken = page.continuation?.takeUnless { next -> next == token },
                    commentParams = page.createCommentParams ?: it.commentParams) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableWatch.update { it.copy(commentsLoading = false, message = readable(error)) }
            }
        }
    }
    fun loadRelated() {
        val snapshot = mutableWatch.value
        val token = snapshot.relatedToken ?: return
        if (snapshot.relatedLoading) return
        mutableWatch.update { it.copy(relatedLoading = true) }
        relatedJob = viewModelScope.launch {
            try {
                val page = app.api.related(token)
                mutableWatch.update { it.copy(relatedLoading = false,
                    related = (it.related + page.videos).distinctBy(Video::id).takeLast(120).toPersistentList(),
                    relatedToken = page.continuation?.takeUnless { next -> next == token }) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableWatch.update { it.copy(relatedLoading = false, message = readable(error)) }
            }
        }
    }
    private fun action(block: suspend () -> String) {
        if (mutableWatch.value.actionPending) return
        mutableWatch.update { it.copy(actionPending = true, message = null) }
        actionJob = viewModelScope.launch {
            try { val message = block(); mutableWatch.update { it.copy(message = message) } }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableWatch.update { it.copy(message = readable(error)) }
            } finally { mutableWatch.update { it.copy(actionPending = false) } }
        }
    }
    fun rate(like: Boolean) {
        val video = mutableWatch.value.video ?: return
        action { app.api.rate(video.id, like); if (like) "Like sent to YouTube." else "Dislike sent to YouTube." }
    }
    fun subscribe() {
        val snapshot = mutableWatch.value
        val video = snapshot.video ?: return
        val enabled = snapshot.subscribed != true
        action {
            app.api.subscribe(video.channelId, enabled)
            mutableWatch.update { it.copy(subscribed = enabled) }
            if (enabled) "Subscribed." else "Unsubscribed."
        }
    }
    fun comment(text: String) {
        val params = mutableWatch.value.commentParams ?: return
        action {
            app.api.postComment(params, text)
            mutableWatch.update { it.copy(comments = persistentListOf(), commentsToken = it.initialCommentsToken) }
            loadComments()
            "Comment sent to YouTube."
        }
    }
    fun setBackground(enabled: Boolean) {
        viewModelScope.launch { app.preferences.setBackgroundAudio(enabled); engine.visibility(foreground, enabled) }
    }
    fun visibility(visible: Boolean) { foreground = visible; engine.visibility(visible, backgroundAudio.value) }
    fun signedIn() { refresh() }
    fun signOut() {
        closeVideo()
        viewModelScope.launch {
            try {
                app.sessions.clear()
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                WebStorage.getInstance().deleteAllData()
                selectFeed(Feed.Home)
            } catch (_: Exception) { mutableFeed.update { it.copy(error = "Could not clear session. Clear application data in Android settings.") } }
        }
    }
    override fun onCleared() {
        controllerFuture?.let(MediaController::releaseFuture)
        controllerFuture = null; controller = null
    }
    private fun readable(error: Exception): String = when (error) {
        is ApiException -> error.message ?: "YouTube request failed."
        is IllegalArgumentException -> error.message ?: "This action is unavailable."
        else -> "Request failed. Check your connection, then retry. YouTube's unofficial API may have changed."
    }
}
