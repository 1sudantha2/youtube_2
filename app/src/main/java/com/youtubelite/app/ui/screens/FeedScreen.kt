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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.youtubelite.app.AppGraph
import com.youtubelite.app.data.FeedRepository
import com.youtubelite.app.model.FeedSource
import com.youtubelite.app.model.Video
import com.youtubelite.app.ui.components.VideoCard
import com.youtubelite.app.ui.components.VideoCardSkeleton
import com.youtubelite.app.ui.components.rememberShimmerPhase
import com.youtubelite.app.ui.nav.NavState
import com.youtubelite.app.ui.nav.Screen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class FeedViewModel(private val source: FeedSource) : ViewModel() {

    private val _videos = MutableStateFlow<List<Video>>(emptyList())
    val videos: StateFlow<List<Video>> = _videos

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    private val _appending = MutableStateFlow(false)
    val appending: StateFlow<Boolean> = _appending

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _signedOut = MutableStateFlow(false)
    val signedOut: StateFlow<Boolean> = _signedOut

    private val _hasMore = MutableStateFlow(true)
    val hasMore: StateFlow<Boolean> = _hasMore

    private var nextToken: String? = null
    private var inFlight = false

    init {
        refresh()
    }

    fun refresh() {
        if (source.requiresAuth && !AppGraph.auth.isSignedIn) {
            _signedOut.value = true
            _videos.value = emptyList()
            _hasMore.value = true
            return
        }
        if (inFlight) return
        viewModelScope.launch {
            inFlight = true
            _loading.value = true
            _error.value = null
            try {
                val page = FeedRepository.browse(source.browseId, null)
                _videos.value = page.videos
                nextToken = page.nextToken
                _hasMore.value = page.nextToken != null
                _signedOut.value = false
            } catch (t: Throwable) {
                _error.value = t.message ?: "Failed to load"
            } finally {
                _loading.value = false
                inFlight = false
            }
        }
    }

    fun loadMore() {
        if (inFlight || !_hasMore.value || _videos.value.isEmpty()) return
        if (source.requiresAuth && !AppGraph.auth.isSignedIn) return
        viewModelScope.launch {
            inFlight = true
            _appending.value = true
            try {
                val page = FeedRepository.browse(source.browseId, nextToken)
                if (page.videos.isNotEmpty()) {
                    _videos.value = _videos.value + page.videos
                    nextToken = page.nextToken
                    _hasMore.value = page.nextToken != null
                } else {
                    _hasMore.value = false
                }
            } catch (_: Throwable) {
                _hasMore.value = false
            } finally {
                _appending.value = false
                inFlight = false
            }
        }
    }

    companion object {
        fun factory(source: FeedSource) = viewModelFactory {
            initializer { FeedViewModel(source) }
        }
    }
}

/**
 * Generic feed screen (Home / Subscriptions / Library / History / Liked).
 *
 * Performance contract:
 *  - items() declares key = { video.id } + contentType for optimal recycling
 *  - footer pagination triggers off composition, not scroll listeners
 *  - back-to-top button derives from firstVisibleItemIndex inside
 *    derivedStateOf, so the flag only flips at the threshold
 *  - skeleton rows share ONE infinite transition, read in the draw phase
 */
@Composable
fun FeedScreen(
    source: FeedSource,
    nav: NavState,
    headerExtra: (@Composable () -> Unit)? = null,
) {
    val vm: FeedViewModel = viewModel(
        key = "feed_${source.browseId}",
        factory = FeedViewModel.factory(source),
    )
    val videos by vm.videos.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val appending by vm.appending.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val signedOut by vm.signedOut.collectAsStateWithLifecycle()
    val hasMore by vm.hasMore.collectAsStateWithLifecycle()

    val listState = rememberLazyListState()
    val phase = rememberShimmerPhase()
    val scope = rememberCoroutineScope()

    val openVideo = remember(nav) {
        { video: Video -> nav.push(Screen.Watch(video.id)) }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = source.title,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { nav.push(Screen.Search) }) {
                    Icon(Icons.Filled.Search, contentDescription = "Search")
                }
                IconButton(onClick = { vm.refresh() }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                }
            }
            headerExtra?.invoke()

            when {
                signedOut -> SignInPrompt(source = source, onSignIn = { nav.push(Screen.Login) })

                error != null && videos.isEmpty() -> ErrorState(
                    message = error ?: "Failed to load",
                    onRetry = { vm.refresh() },
                )

                videos.isEmpty() && loading -> LazyColumn(Modifier.fillMaxSize()) {
                    items(8, key = { "skel_$it" }, contentType = { "skeleton" }) {
                        VideoCardSkeleton(phase)
                    }
                }

                else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(
                        items = videos,
                        key = { it.id },
                        contentType = { "video" },
                    ) { video ->
                        VideoCard(video = video, onClick = openVideo)
                    }
                    if (hasMore) {
                        item(key = "footer", contentType = "footer") {
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

        // Derived state: recomposition only when the boolean flips, not per scroll frame.
        val showJumpToTop by remember {
            derivedStateOf { listState.firstVisibleItemIndex > 15 }
        }
        if (showJumpToTop) {
            TextButton(
                onClick = { scope.launch { listState.scrollToItem(0) } },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
            ) { Text("Top") }
        }
    }
}

@Composable
private fun SignInPrompt(source: FeedSource, onSignIn: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.Lock, contentDescription = null)
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Sign in to see your ${source.title.lowercase()}",
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onSignIn) { Text("Sign in with Google") }
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = onRetry) { Text("Retry") }
    }
}
