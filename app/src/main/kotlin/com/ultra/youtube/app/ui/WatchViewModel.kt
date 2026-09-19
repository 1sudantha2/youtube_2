package com.ultra.youtube.app.ui

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ultra.youtube.app.data.YoutubeRepository
import com.ultra.youtube.app.data.innertube.InnerTubeClient
import com.ultra.youtube.app.domain.ActionResult
import com.ultra.youtube.app.domain.Comment
import com.ultra.youtube.app.domain.VideoDetail
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@Immutable
data class WatchUiState(
    val detail: VideoDetail? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
    val comments: List<Comment> = emptyList(),
    val commentsContinuation: String? = null,
    val isLoadingComments: Boolean = false,
    val isLiked: Boolean = false,
    val isDisliked: Boolean = false,
    val isSubscribed: Boolean = false,
    val actionMessage: String? = null,
)

/**
 * Drives the watch screen: metadata, comments, and the like/dislike/subscribe actions.
 *
 * Actions are optimistic-free by design — InnerTube tells us whether the write landed, and
 * showing a spinner for ~200 ms is cheaper (and less confusing) than rolling back a fake
 * toggle.
 */
class WatchViewModel(
    private val repository: YoutubeRepository,
    private val videoId: String,
) : ViewModel() {

    private val _uiState = MutableStateFlow(WatchUiState())
    val uiState: StateFlow<WatchUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            runCatching { repository.videoDetail(videoId) }
                .onSuccess { detail ->
                    _uiState.value = _uiState.value.copy(
                        detail = detail,
                        isLoading = false,
                    )
                    if (detail.commentsContinuation != null) loadComments()
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = error.message ?: "Could not load this video",
                    )
                }
        }
    }

    fun loadComments() {
        val state = _uiState.value
        if (state.isLoadingComments) return
        _uiState.value = state.copy(isLoadingComments = true)
        viewModelScope.launch {
            runCatching { repository.comments(videoId, state.commentsContinuation) }
                .onSuccess { page ->
                    val current = _uiState.value
                    _uiState.value = current.copy(
                        comments = (current.comments + page.comments).distinctBy { it.id },
                        commentsContinuation = page.continuation,
                        isLoadingComments = false,
                    )
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(isLoadingComments = false)
                }
        }
    }

    fun like() {
        val detail = _uiState.value.detail ?: return
        viewModelScope.launch {
            val result = if (_uiState.value.isLiked) {
                repository.removeLike(videoId, detail.likeParams)
            } else {
                repository.like(videoId, detail.likeParams)
            }
            applyAction(result) { copy(isLiked = result.success && !isLiked, isDisliked = false) }
        }
    }

    fun dislike() {
        val detail = _uiState.value.detail ?: return
        viewModelScope.launch {
            val result = repository.dislike(videoId, detail.dislikeParams)
            applyAction(result) { copy(isDisliked = result.success && !isDisliked, isLiked = false) }
        }
    }

    fun toggleSubscribe() {
        val detail = _uiState.value.detail ?: return
        val channelId = detail.video.channelId.takeIf { it.isNotBlank() } ?: return
        viewModelScope.launch {
            val result = if (_uiState.value.isSubscribed) {
                repository.unsubscribe(channelId, detail.subscribeParams)
            } else {
                repository.subscribe(channelId, detail.subscribeParams)
            }
            applyAction(result) { copy(isSubscribed = result.success && !isSubscribed) }
        }
    }

    fun postComment(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            val result = repository.postComment(videoId, text.trim())
            applyAction(result) {
                if (result.success) copy(commentsContinuation = null) else this
            }
            if (result.success) {
                _uiState.value = _uiState.value.copy(comments = emptyList())
                loadComments()
            }
        }
    }

    private inline fun applyAction(result: ActionResult, transform: WatchUiState.() -> WatchUiState) {
        _uiState.value = _uiState.value.transform().copy(
            actionMessage = if (result.success) null else result.statusText.ifBlank { "Sign in required" },
        )
    }

    companion object {
        fun factory(repository: YoutubeRepository, videoId: String): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    WatchViewModel(repository, videoId) as T
            }

        /** Kept for callers that want to log which profile served the watch payload. */
        val defaultProfile: InnerTubeClient.ClientProfile get() = InnerTubeClient.ANDROID
    }
}
