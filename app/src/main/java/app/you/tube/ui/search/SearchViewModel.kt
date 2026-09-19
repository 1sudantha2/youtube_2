package app.you.tube.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.you.tube.core.model.SearchUiState
import app.you.tube.di.AppContainer
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SearchViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private var searchJob: Job? = null

    init {
        // Debounced suggestions: re-queried only after the query settles.
        viewModelScope.launch {
            _state
                .map { it.query }
                .distinctUntilChanged()
                .collectLatest { query ->
                    delay(280)
                    if (query.length < 2) {
                        _state.update { it.copy(suggestions = kotlinx.collections.immutable.persistentListOf()) }
                        return@collectLatest
                    }
                    val suggestions = runCatching {
                        container.repository.suggestions(query)
                    }.getOrDefault(emptyList())
                    if (_state.value.query == query) {
                        _state.update { it.copy(suggestions = suggestions.toImmutableList()) }
                    }
                }
        }
    }

    fun updateQuery(query: String) {
        _state.update { it.copy(query = query) }
    }

    fun search(query: String = _state.value.query) {
        val q = query.trim()
        if (q.isEmpty()) return
        searchJob?.cancel()
        _state.update {
            SearchUiState(
                query = q,
                suggestions = it.suggestions,
                searched = true,
                isLoading = true
            )
        }
        searchJob = viewModelScope.launch {
            runCatching { container.repository.search(q, continuation = null) }
                .onSuccess { (videos, channels, token) ->
                    _state.update {
                        it.copy(
                            results = videos.toImmutableList(),
                            channels = channels.toImmutableList(),
                            continuation = token,
                            isLoading = false,
                            error = null
                        )
                    }
                }
                .onFailure { t ->
                    _state.update {
                        it.copy(isLoading = false, error = t.message ?: "Search failed")
                    }
                }
        }
    }

    fun loadMore() {
        val current = _state.value
        val token = current.continuation ?: return
        if (current.isLoadingMore || current.isLoading) return
        _state.update { it.copy(isLoadingMore = true) }
        viewModelScope.launch {
            runCatching { container.repository.search(current.query, continuation = token) }
                .onSuccess { (videos, channels, next) ->
                    _state.update { st ->
                        val fresh = videos.filter { v -> st.results.none { it.id == v.id } }
                        st.copy(
                            results = (st.results + fresh).toImmutableList(),
                            continuation = next,
                            isLoadingMore = false
                        )
                    }
                }
                .onFailure { _state.update { it.copy(isLoadingMore = false) } }
        }
    }
}
