package app.you.tube.ui.subscriptions

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

/** FEsubscriptions feed (requires sign-in). */
class SubscriptionsViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(FeedUiState(isLoadingInitial = true, requireAuth = !container.auth.isLoggedIn))
    val state: StateFlow<FeedUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        if (!container.auth.isLoggedIn) {
            _state.update { FeedUiState(requireAuth = true) }
            return
        }
        _state.update { FeedUiState(isLoadingInitial = true) }
        viewModelScope.launch {
            runCatching { container.repository.subscriptionsFeed(continuation = null) }
                .onSuccess { (videos, token) ->
                    _state.update { FeedUiState(items = videos.toImmutableList(), continuation = token) }
                }
                .onFailure { t ->
                    _state.update { FeedUiState(error = t.message ?: "Failed to load subscriptions") }
                }
        }
    }

    fun loadMore() {
        val current = _state.value
        val token = current.continuation ?: return
        if (current.isLoadingMore || current.isLoadingInitial || current.requireAuth) return
        _state.update { it.copy(isLoadingMore = true) }
        viewModelScope.launch {
            runCatching { container.repository.subscriptionsFeed(continuation = token) }
                .onSuccess { (videos, next) ->
                    _state.update { st ->
                        val fresh = videos.filter { v -> st.items.none { it.id == v.id } }
                        st.copy(items = (st.items + fresh).toImmutableList(), continuation = next, isLoadingMore = false)
                    }
                }
                .onFailure { _state.update { it.copy(isLoadingMore = false) } }
        }
    }
}
