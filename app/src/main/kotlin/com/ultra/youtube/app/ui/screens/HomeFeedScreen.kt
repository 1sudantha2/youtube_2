package com.ultra.youtube.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ultra.youtube.app.domain.FeedItem
import com.ultra.youtube.app.domain.VideoItem
import com.ultra.youtube.app.ui.FeedViewModel
import com.ultra.youtube.app.ui.components.FeedError
import com.ultra.youtube.app.ui.components.FeedSkeleton
import com.ultra.youtube.app.ui.components.LoadMoreFooter
import com.ultra.youtube.app.ui.components.VideoCard

/**
 * The home feed (`FEwhat_to_watch`).
 *
 * Scroll-performance notes, in the order they matter:
 *
 *  1. **Explicit `key` on every item.** With stable keys, `LazyColumn` reuses the same
 *     composition slots while scrolling and never re-creates item state; without them a
 *     page insert shifts every following item and recomposes the visible window.
 *  2. **`@Immutable` [VideoItem]** inside `@Immutable` [FeedUiState]: item composables are
 *     skipped, not re-executed.
 *  3. **`derivedStateOf` for the paging trigger.** The scroll-offset read happens in a
 *     derived state, so a scroll frame does not recompose the screen — only crossing the
 *     threshold does, and only once.
 *  4. **`contentType` per item type**, so a video row is never recycled into a shelf and
 *     re-measured.
 */
@Composable
fun HomeFeedScreen(
    viewModel: FeedViewModel,
    onVideoClick: (VideoItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    FeedScreen(
        viewModel = viewModel,
        title = "Home",
        onVideoClick = onVideoClick,
        modifier = modifier,
    )
}

/**
 * The list implementation shared by every feed screen. One code path means one set of
 * scroll-performance decisions to get right.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(
    viewModel: FeedViewModel,
    title: String,
    onVideoClick: (VideoItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    // Paging trigger: read the layout info inside a derived state so ordinary scroll
    // frames do not recompose the screen.
    val shouldLoadMore by remember(state.videos.size) {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            state.videos.isNotEmpty() && lastVisible >= layoutInfo.totalItemsCount - 4
        }
    }
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) viewModel.loadMore()
    }

    Column(modifier = modifier.fillMaxSize()) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 8.dp),
        )

        when {
            state.isRefreshing && state.videos.isEmpty() -> FeedSkeleton()
            state.error != null && state.videos.isEmpty() ->
                FeedError(message = state.error.orEmpty(), onRetry = viewModel::refresh)

            else -> PullToRefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = viewModel::refresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.Top,
                ) {
                    items(
                        count = state.videos.size,
                        // Stable keys: the single most important line for scroll cost.
                        key = { index -> state.videos[index].id },
                        contentType = { "video" },
                    ) { index ->
                        val video = state.videos[index]
                        VideoCard(
                            video = video,
                            onClick = { onVideoClick(video) },
                        )
                    }

                    if (state.shelves.isNotEmpty()) {
                        items(
                            items = state.shelves,
                            key = { it.key },
                            contentType = { "shelf" },
                        ) { shelf ->
                            ShelfRow(shelf = shelf, onVideoClick = onVideoClick)
                        }
                    }

                    item(key = "__footer__", contentType = "footer") {
                        LoadMoreFooter(isLoading = state.isLoadingMore)
                    }
                }
            }
        }
    }
}

@Composable
private fun ShelfRow(shelf: FeedItem.Shelf, onVideoClick: (VideoItem) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = shelf.title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
        )
        shelf.videos.forEach { video ->
            VideoCard(video = video, onClick = { onVideoClick(video) })
        }
    }
}

