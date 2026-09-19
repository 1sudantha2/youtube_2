package com.ultra.youtube.app.ui

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ultra.youtube.app.data.YoutubeRepository
import com.ultra.youtube.app.domain.FeedItem
import com.ultra.youtube.app.domain.VideoItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Shared state container for every list screen (home, subscriptions, library, watch later,
 * search).
 *
 * [FeedUiState] is `@Immutable` with only immutable collections, so `collectAsState()`
 * recomposes a screen exactly when the feed actually changed.
 */
@Immutable
data class FeedUiState(
    val videos: List<VideoItem> = emptyList(),
    val shelves: List<FeedItem.Shelf> = emptyList(),
    val continuation: String? = null,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val error: String? = null,
) {
    /** Stable keys for `LazyColumn(key = …)`; never re-order under an active scroll. */
    val keys: List<String> get() = videos.map { "v:${it.id}" }
}

class FeedViewModel(
    private val repository: YoutubeRepository,
    private val source: FeedSource,
) : ViewModel() {

    /** What this instance is listing. Search re-creates the VM when the query changes. */
    sealed interface FeedSource {
        data object Home : FeedSource
        data object Subscriptions : FeedSource
        data object Library : FeedSource
        data object History : FeedSource
        data class WatchLater(val playlistId: String) : FeedSource
        data class Search(val query: String) : FeedSource
        data class Channel(val channelId: String) : FeedSource
    }

    private val _uiState = MutableStateFlow(FeedUiState())
    val uiState: StateFlow<FeedUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        loadJob?.cancel()
        _uiState.value = _uiState.value.copy(isRefreshing = true, error = null)
        loadJob = viewModelScope.launch {
            runCatching { fetch(continuation = null) }
                .onSuccess { result ->
                    _uiState.value = FeedUiState(
                        videos = result.videos,
                        shelves = result.shelves,
                        continuation = result.continuation,
                    )
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        isRefreshing = false,
                        error = error.message ?: "Unknown error",
                    )
                }
        }
    }

    fun loadMore() {
        val state = _uiState.value
        val token = state.continuation ?: return
        if (state.isLoadingMore || state.isRefreshing) return

        _uiState.value = state.copy(isLoadingMore = true)
        viewModelScope.launch {
            runCatching { fetch(token) }
                .onSuccess { result ->
                    val current = _uiState.value
                    _uiState.value = current.copy(
                        // Deduplicate: YouTube repeats videos across continuation pages.
                        videos = (current.videos + result.videos).distinctBy { it.id },
                        shelves = (current.shelves + result.shelves).distinctBy { it.key },
                        continuation = result.continuation,
                        isLoadingMore = false,
                    )
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(isLoadingMore = false)
                }
        }
    }

    /** Watch Later is addressed by playlist id, which differs per account. */
    private var resolvedWatchLaterId: String? = null

    private suspend fun fetch(continuation: String?): YoutubeRepository.FeedResult =
        when (val source = source) {
            FeedSource.Home -> repository.homeFeed(continuation)
            FeedSource.Subscriptions -> repository.subscriptions(continuation)
            FeedSource.Library -> repository.library(continuation)
            FeedSource.History -> repository.history(continuation)
            is FeedSource.WatchLater -> {
                val id = source.playlistId.ifBlank {
                    resolvedWatchLaterId
                        ?: repository.watchLaterPlaylistId()
                        ?: "VL"
                }.also { resolvedWatchLaterId = it }
                repository.watchLater(id, continuation)
            }
            is FeedSource.Search -> repository.search(source.query, continuation)
            is FeedSource.Channel -> repository.channel(source.channelId, continuation)
        }

    companion object {
        fun factory(repository: YoutubeRepository, source: FeedSource): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    FeedViewModel(repository, source) as T
            }
    }
}
