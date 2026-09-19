package com.youtubelite.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.youtubelite.app.data.FeedRepository
import com.youtubelite.app.model.Video
import com.youtubelite.app.ui.components.VideoCard
import com.youtubelite.app.ui.nav.NavState
import com.youtubelite.app.ui.nav.Screen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class SearchViewModel : ViewModel() {

    private val _videos = MutableStateFlow<List<Video>>(emptyList())
    val videos: StateFlow<List<Video>> = _videos

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    private val _appending = MutableStateFlow(false)
    val appending: StateFlow<Boolean> = _appending

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _hasMore = MutableStateFlow(false)
    val hasMore: StateFlow<Boolean> = _hasMore

    private var query: String? = null
    private var token: String? = null
    private var inFlight = false

    fun submit(q: String) {
        val trimmed = q.trim()
        if (trimmed.isEmpty() || trimmed == query) return
        query = trimmed
        token = null
        if (inFlight) return
        viewModelScope.launch { load(initial = true) }
    }

    fun loadMore() {
        if (inFlight || token == null) return
        viewModelScope.launch { load(initial = false) }
    }

    private suspend fun load(initial: Boolean) {
        val q = query ?: return
        inFlight = true
        if (initial) _loading.value = true else _appending.value = true
        _error.value = null
        try {
            val page = FeedRepository.search(q, if (initial) null else token)
            if (initial) {
                _videos.value = page.videos
            } else {
                _videos.value = _videos.value + page.videos
            }
            token = page.nextToken
            _hasMore.value = page.nextToken != null
        } catch (t: Throwable) {
            if (initial) {
                _error.value = t.message ?: "Search failed"
                _videos.value = emptyList()
            }
            _hasMore.value = false
        } finally {
            _loading.value = false
            _appending.value = false
            inFlight = false
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { SearchViewModel() }
        }
    }
}

@Composable
fun SearchScreen(nav: NavState) {
    val vm: SearchViewModel = viewModel(factory = SearchViewModel.Factory)
    val videos by vm.videos.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val appending by vm.appending.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val hasMore by vm.hasMore.collectAsStateWithLifecycle()

    var query by rememberSaveable { mutableStateOf("") }
    val openVideo = remember(nav) {
        { video: Video -> nav.push(Screen.Watch(video.id)) }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { nav.pop() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp),
                placeholder = { Text("Search YouTube") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.submit(query) }),
            )
            IconButton(onClick = { vm.submit(query) }) {
                Icon(Icons.Filled.Search, contentDescription = "Search")
            }
        }

        when {
            error != null && videos.isEmpty() -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(error ?: "", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { vm.submit(query) }) { Text("Retry") }
                }
            }

            loading && videos.isEmpty() -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp) }

            videos.isEmpty() -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { Text("Search for videos", color = MaterialTheme.colorScheme.onSurfaceVariant) }

            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(
                    items = videos,
                    key = { it.id },
                    contentType = { "video" },
                ) { video -> VideoCard(video, openVideo) }
                if (hasMore) {
                    item(key = "footer") {
                        LaunchedEffect(videos.size) { vm.loadMore() }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (appending) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(22.dp),
                                    strokeWidth = 2.dp,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
