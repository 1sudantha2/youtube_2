package app.you.tube.player

import androidx.compose.runtime.Immutable
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.session.MediaController
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.you.tube.core.model.CommentItem
import app.you.tube.core.model.CommentSortOption
import app.you.tube.core.network.LikeAction
import app.you.tube.core.model.LikeState
import app.you.tube.core.model.StreamBundle
import app.you.tube.core.model.StreamQuality
import app.you.tube.core.model.VideoItem
import app.you.tube.data.SettingsStore
import app.you.tube.di.AppContainer
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class PlayerUiState(
    val videoId: String,
    val isLoading: Boolean = false,
    val error: String? = null,
    val bundle: StreamBundle? = null,
    val nextVideo: VideoItem? = null,
    val isSubscribed: Boolean = false,
    val likeState: LikeState = LikeState.NONE,
    val selectedQualityItag: Int? = null,
    val commentsToken: String? = null,
    val comments: CommentsUiState = CommentsUiState()
)

@Immutable
data class CommentsUiState(
    val isLoading: Boolean = false,
    val items: ImmutableList<CommentItem> = persistentListOf(),
    val continuation: String? = null,
    val createCommentParams: String? = null,
    val sorts: ImmutableList<CommentSortOption> = persistentListOf(),
    val replies: ImmutableMap<String, ImmutableList<CommentItem>> = persistentMapOf(),
    val loadingRepliesFor: String? = null,
    val posting: Boolean = false,
    val error: String? = null
)

/**
 * Owns the UI side of playback: a MediaController bound to [PlaybackService].
 *
 * All network/extraction work runs in viewModelScope on Dispatchers.IO
 * (inside the repository); all player commands go through the controller.
 * Fine-grained StateFlows (isPlaying / playbackState / aspectRatio) keep
 * recomposition scopes tiny — the UI never re-renders more than it must.
 */
class PlayerViewModel(
    initialVideoId: String,
    private val container: AppContainer
) : ViewModel() {

    private val repository = container.repository

    private val _uiState = MutableStateFlow(PlayerUiState(videoId = initialVideoId))
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private val _controller = MutableStateFlow<MediaController?>(null)
    val controller: StateFlow<MediaController?> = _controller.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _playbackState = MutableStateFlow(Player.STATE_IDLE)
    val playbackState: StateFlow<Int> = _playbackState.asStateFlow()

    private val _videoAspectRatio = MutableStateFlow(16f / 9f)
    val videoAspectRatio: StateFlow<Float> = _videoAspectRatio.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val events: SharedFlow<String> = _events.asSharedFlow()

    private var autoplay = true
    private var defaultQuality = SettingsStore.QUALITY_AUTO
    private var listener: Player.Listener? = null
    private var playbackStarted = false
    private val rebufferTimestamps = mutableListOf<Long>()

    init {
        viewModelScope.launch { container.settings.autoplay.collect { autoplay = it } }
        viewModelScope.launch { container.settings.defaultQuality.collect { defaultQuality = it } }
        viewModelScope.launch { connectController() }
        load(initialVideoId)
    }

    // ------------------------------------------------------------ loading

    fun playVideo(videoId: String) {
        if (videoId == _uiState.value.videoId) return
        load(videoId)
    }

    private fun load(videoId: String) {
        playbackStarted = false
        _uiState.update { PlayerUiState(videoId = videoId, isLoading = true) }
        viewModelScope.launch {
            repository.extractStreams(videoId)
                .onSuccess { bundle ->
                    repository.registerBundle(bundle)
                    _uiState.update {
                        it.copy(
                            videoId = videoId,
                            isLoading = false,
                            error = null,
                            bundle = bundle,
                            nextVideo = bundle.related.firstOrNull { v -> !v.isShort && !v.isLive }
                                ?: bundle.related.firstOrNull()
                        )
                    }
                    maybeStartPlayback()
                }
                .onFailure { t ->
                    _uiState.update { it.copy(isLoading = false, error = t.message ?: "Stream extraction failed") }
                }
        }
        viewModelScope.launch {
            runCatching { repository.watchNext(videoId) }
                .onSuccess { info ->
                    _uiState.update { it.copy(commentsToken = info.commentsToken, isSubscribed = info.isSubscribed) }
                }
        }
    }

    private suspend fun connectController() {
        try {
            val controller = container.playerConnection.buildControllerFuture().awaitFuture()
            if (_controller.value == null) {
                _controller.value = controller
                attachListener(controller)
                maybeStartPlayback()
            } else {
                controller.release()
            }
        } catch (t: Throwable) {
            _events.tryEmit("Playback engine unavailable: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    // ----------------------------------------------------------- playback

    private fun maybeStartPlayback() {
        if (playbackStarted) return
        val controller = _controller.value ?: return
        val bundle = _uiState.value.bundle ?: return
        startPlayback(controller, bundle, chooseDefaultQuality(bundle), 0L)
    }

    private fun chooseDefaultQuality(bundle: StreamBundle): StreamQuality? {
        val qualities = bundle.qualities
        return when (defaultQuality) {
            SettingsStore.QUALITY_AUTO ->
                qualities.firstOrNull { it.height <= 1080 } ?: qualities.firstOrNull() ?: bundle.fallbackMuxed
            else ->
                qualities.firstOrNull { it.label == defaultQuality }
                    ?: qualities.firstOrNull { it.height <= 1080 }
                    ?: qualities.firstOrNull()
                    ?: bundle.fallbackMuxed
        }
    }

    private fun startPlayback(
        controller: MediaController,
        bundle: StreamBundle,
        quality: StreamQuality?,
        startPositionMs: Long
    ) {
        val item = buildMediaItem(bundle, quality)
        if (item == null) {
            _events.tryEmit("No playable stream found for this video")
            return
        }
        playbackStarted = true
        rebufferTimestamps.clear()
        _uiState.update { it.copy(selectedQualityItag = quality?.itag) }
        controller.setMediaItem(item, startPositionMs)
        controller.prepare()
        controller.play()
    }

    private fun buildMediaItem(bundle: StreamBundle, quality: StreamQuality?): MediaItem? {
        if (bundle.isLive && !bundle.hlsUrl.isNullOrBlank()) {
            return MediaItem.Builder()
                .setMediaId(bundle.videoId)
                .setUri(bundle.hlsUrl)
                .setMimeType(MimeTypes.APPLICATION_M3U8)
                .build()
        }
        val q = quality ?: return null
        val audioUrl = bundle.bestAudioUrl
        return if (!q.isMuxed && !audioUrl.isNullOrBlank() && bundle.bestAudioItag != null) {
            // Video-only itag merged with audio-only itag (resolved service-side).
            MediaItem.Builder()
                .setMediaId(bundle.videoId)
                .setUri(PlayerMediaSourceFactory.mergedUri(bundle.videoId, q.itag, bundle.bestAudioItag!!))
                .build()
        } else {
            MediaItem.Builder().setMediaId(bundle.videoId).setUri(q.url).build()
        }
    }

    /** Seamless quality switch preserving the exact playback position. */
    fun selectQuality(quality: StreamQuality) {
        val controller = _controller.value ?: return
        val bundle = _uiState.value.bundle ?: return
        val position = if (controller.playbackState == Player.STATE_ENDED) 0L else controller.currentPosition
        startPlayback(controller, bundle, quality, position)
    }

    fun togglePlayPause() {
        val c = _controller.value ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    fun seekTo(positionMs: Long) {
        _controller.value?.seekTo(positionMs)
    }

    /**
     * Background/foreground transition. When the app leaves the foreground the
     * video track is disabled — the video decoder is released immediately and
     * playback continues audio-only in the service (max battery savings with
     * the screen off). Re-enabled on return.
     */
    fun setVideoTrackEnabled(enabled: Boolean) {
        val c = _controller.value ?: return
        runCatching {
            c.trackSelectionParameters = c.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !enabled)
                .build()
        }
    }

    private fun attachListener(controller: MediaController) {
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                _playbackState.value = playbackState
                if (playbackState == Player.STATE_BUFFERING && _isPlaying.value) {
                    val now = System.currentTimeMillis()
                    rebufferTimestamps.add(now)
                    rebufferTimestamps.removeAll { now - it > REBUFFER_WINDOW_MS }
                    maybeFallbackQuality()
                }
                if (playbackState == Player.STATE_ENDED) maybeAutoplayNext()
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                val w = videoSize.width * videoSize.pixelWidthHeightRatio
                val h = videoSize.height
                if (w > 0f && h > 0f) {
                    _videoAspectRatio.value = (w / h).coerceIn(0.4f, 2.4f)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val bundle = _uiState.value.bundle
                val fallback = bundle?.fallbackMuxed
                if (fallback != null &&
                    !bundle.isLive &&
                    _uiState.value.selectedQualityItag != fallback.itag
                ) {
                    _events.tryEmit("Playback error — switched to standard quality")
                    selectQuality(fallback)
                } else {
                    _events.tryEmit("Playback error: ${error.errorCodeName}")
                }
            }
        }
        controller.addListener(l)
        listener = l
    }

    /** Network degradation guard: >=3 rebuffers in 30s drops to the muxed fallback. */
    private fun maybeFallbackQuality() {
        if (rebufferTimestamps.size < REBUFFER_TRIGGER_COUNT) return
        val state = _uiState.value
        val bundle = state.bundle ?: return
        val fallback = bundle.fallbackMuxed ?: return
        if (bundle.isLive) return
        val current = state.selectedQualityItag
        val currentQuality = bundle.qualityByItag(current)
        if (currentQuality != null && currentQuality.isMuxed) return
        if (current == fallback.itag) return
        _events.tryEmit("Slow network — switched to ${fallback.label} standard quality")
        selectQuality(fallback)
    }

    private fun maybeAutoplayNext() {
        if (!autoplay) return
        val next = _uiState.value.nextVideo ?: return
        if (_uiState.value.bundle?.isLive == true) return
        playVideo(next.id)
    }

    // --------------------------------------------------------- engagement

    fun toggleLike() {
        val state = _uiState.value
        val bundle = state.bundle ?: return
        if (!container.auth.isLoggedIn) {
            _events.tryEmit("Sign in to like videos")
            return
        }
        val previous = state.likeState
        val action = when (previous) {
            LikeState.LIKE -> LikeAction.REMOVE
            LikeState.DISLIKE -> LikeAction.LIKE
            LikeState.NONE -> LikeAction.LIKE
        }
        val optimistic = when (action) {
            LikeAction.LIKE -> LikeState.LIKE
            LikeAction.DISLIKE -> LikeState.DISLIKE
            LikeAction.REMOVE -> LikeState.NONE
        }
        _uiState.update { it.copy(likeState = optimistic) }
        viewModelScope.launch {
            runCatching { repository.likeVideo(bundle.videoId, action) }
                .onFailure {
                    _uiState.update { s -> s.copy(likeState = previous) }
                    _events.tryEmit("Could not send like — please try again")
                }
        }
    }

    fun toggleDislike() {
        val state = _uiState.value
        val bundle = state.bundle ?: return
        if (!container.auth.isLoggedIn) {
            _events.tryEmit("Sign in to dislike videos")
            return
        }
        val previous = state.likeState
        val action = when (previous) {
            LikeState.LIKE -> LikeAction.DISLIKE
            LikeState.DISLIKE -> LikeAction.REMOVE
            LikeState.NONE -> LikeAction.DISLIKE
        }
        val optimistic = when (action) {
            LikeAction.LIKE -> LikeState.LIKE
            LikeAction.DISLIKE -> LikeState.DISLIKE
            LikeAction.REMOVE -> LikeState.NONE
        }
        _uiState.update { it.copy(likeState = optimistic) }
        viewModelScope.launch {
            runCatching { repository.likeVideo(bundle.videoId, action) }
                .onFailure {
                    _uiState.update { s -> s.copy(likeState = previous) }
                    _events.tryEmit("Could not send dislike — please try again")
                }
        }
    }

    fun toggleSubscribe() {
        val bundle = _uiState.value.bundle ?: return
        if (bundle.channelId.isBlank()) return
        if (!container.auth.isLoggedIn) {
            _events.tryEmit("Sign in to subscribe")
            return
        }
        val target = !_uiState.value.isSubscribed
        _uiState.update { it.copy(isSubscribed = target) }
        viewModelScope.launch {
            runCatching { repository.subscribeChannel(bundle.channelId, target) }
                .onFailure {
                    _uiState.update { s -> s.copy(isSubscribed = !target) }
                    _events.tryEmit("Could not update subscription")
                }
        }
    }

    // ------------------------------------------------------------ comments

    fun ensureCommentsLoaded() {
        val state = _uiState.value
        val token = state.commentsToken ?: return
        if (state.comments.isLoading || state.comments.items.isNotEmpty()) return
        loadComments(token, replace = false)
    }

    fun loadMoreComments() {
        val state = _uiState.value
        val token = state.comments.continuation ?: return
        if (state.comments.isLoading) return
        loadComments(token, replace = false)
    }

    fun changeCommentSort(option: CommentSortOption) {
        val token = _uiState.value.commentsToken ?: return
        loadComments(option.token, replace = true)
    }

    private fun loadComments(token: String, replace: Boolean) {
        _uiState.update { it.copy(comments = it.comments.copy(isLoading = true, error = null)) }
        viewModelScope.launch {
            runCatching { repository.comments(token) }
                .onSuccess { page ->
                    _uiState.update { s ->
                        val merged = if (replace) {
                            page.items
                        } else {
                            val existing = s.comments.items
                            existing + page.items.filter { new -> existing.none { it.id == new.id } }
                        }
                        s.copy(
                            comments = s.comments.copy(
                                isLoading = false,
                                items = merged.toImmutableList(),
                                continuation = page.continuation,
                                createCommentParams = page.createCommentParams ?: s.comments.createCommentParams,
                                sorts = if (page.sorts.isNotEmpty()) page.sorts else s.comments.sorts
                            )
                        )
                    }
                }
                .onFailure { t ->
                    _uiState.update { s -> s.copy(comments = s.comments.copy(isLoading = false, error = t.message ?: "Failed to load comments")) }
                }
        }
    }

    fun loadReplies(comment: CommentItem) {
        val token = comment.repliesToken ?: return
        if (_uiState.value.comments.replies.containsKey(comment.id)) return
        _uiState.update { s -> s.copy(comments = s.comments.copy(loadingRepliesFor = comment.id)) }
        viewModelScope.launch {
            runCatching { repository.comments(token) }
                .onSuccess { page ->
                    _uiState.update { s ->
                        s.copy(
                            comments = s.comments.copy(
                                loadingRepliesFor = null,
                                replies = s.comments.replies.put(comment.id, page.items).toPersistentMap()
                            )
                        )
                    }
                }
                .onFailure {
                    _uiState.update { s -> s.copy(comments = s.comments.copy(loadingRepliesFor = null)) }
                }
        }
    }

    fun toggleCommentLike(comment: CommentItem) {
        if (!container.auth.isLoggedIn) {
            _events.tryEmit("Sign in to like comments")
            return
        }
        val wasLiked = comment.isLiked
        val token = if (wasLiked) comment.unlikeActionToken else comment.likeActionToken
        if (token.isNullOrBlank()) {
            _events.tryEmit("This action is unavailable for this comment")
            return
        }
        updateComment(comment.id) { it.copy(isLiked = !wasLiked) }
        viewModelScope.launch {
            runCatching { repository.commentAction(token) }
                .onFailure {
                    updateComment(comment.id) { it.copy(isLiked = wasLiked) }
                    _events.tryEmit("Could not update comment like")
                }
        }
    }

    fun postComment(text: String) {
        val state = _uiState.value
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val params = state.comments.createCommentParams
        if (params == null) {
            _events.tryEmit("Comment posting is unavailable for this video")
            return
        }
        if (!container.auth.isLoggedIn) {
            _events.tryEmit("Sign in to comment")
            return
        }
        _uiState.update { it.copy(comments = it.comments.copy(posting = true)) }
        viewModelScope.launch {
            val result = runCatching { repository.postComment(params, trimmed) }
            _uiState.update { it.copy(comments = it.comments.copy(posting = false)) }
            result.onSuccess {
                _events.tryEmit("Comment posted")
                _uiState.value.commentsToken?.let { loadComments(it, replace = true) }
            }.onFailure {
                _events.tryEmit("Could not post comment: ${it.message ?: "unknown error"}")
            }
        }
    }

    private fun updateComment(id: String, transform: (CommentItem) -> CommentItem) {
        _uiState.update { s ->
            s.copy(
                comments = s.comments.copy(
                    items = s.comments.items.map { if (it.id == id) transform(it) else it }.toImmutableList()
                )
            )
        }
    }

    // ------------------------------------------------------------- cleanup

    override fun onCleared() {
        listener?.let { l -> _controller.value?.removeListener(l) }
        listener = null
        _controller.value?.release()
        _controller.value = null
        super.onCleared()
    }

    companion object {
        private const val REBUFFER_WINDOW_MS = 30_000L
        private const val REBUFFER_TRIGGER_COUNT = 3
    }
}
