package app.you.tube.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.you.tube.core.model.FeedUiState
import app.you.tube.di.AppContainer
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Home feed (FEwhat_to_watch) with cursor-based continuation paging.
 * All fetch/parse work happens on Dispatchers.IO inside the repository;
 * state emissions are single immutable snapshots (skip-friendly for Compose).
 */
class HomeViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(FeedUiState(isLoadingInitial = true))
    val state: StateFlow<FeedUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _state.update { FeedUiState(isLoadingInitial = true) }
        viewModelScope.launch {
            runCatching { container.repository.homeFeed(continuation = null) }
                .onSuccess { (videos, token) ->
                    _state.update {
                        FeedUiState(items = videos.toImmutableList(), continuation = token)
                    }
                }
                .onFailure { t ->
                    _state.update {
                        FeedUiState(error = t.message ?: "Failed to load the home feed")
                    }
                }
        }
    }

    fun loadMore() {
        val current = _state.value
        val token = current.continuation ?: return
        if (current.isLoadingMore || current.isLoadingInitial) return
        _state.update { it.copy(isLoadingMore = true) }
        viewModelScope.launch {
            runCatching { container.repository.homeFeed(continuation = token) }
                .onSuccess { (videos, next) ->
                    _state.update { st ->
                        val fresh = videos.filter { v -> st.items.none { it.id == v.id } }
                        st.copy(
                            items = (st.items + fresh).toImmutableList(),
                            continuation = next,
                            isLoadingMore = false
                        )
                    }
                }
                .onFailure {
                    _state.update { it.copy(isLoadingMore = false) }
                }
        }
    }
}
