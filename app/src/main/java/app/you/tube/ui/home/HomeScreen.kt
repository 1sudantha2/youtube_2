package app.you.tube.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.paint
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.you.tube.R
import app.you.tube.core.model.VideoItem
import app.you.tube.ui.LocalAppContainer
import app.you.tube.ui.components.ShimmerVideoCard
import app.you.tube.ui.components.VideoCard

/**
 * Home feed — the reference implementation of the app's list-performance
 * contract:
 *
 *  - `key = { it.id }` + explicit `contentType` maximize item recycling and
 *    let Compose reuse composition slots as the feed scrolls.
 *  - Prefetch trigger lives in a `derivedStateOf` read — the "near the end"
 *    predicate is evaluated as derived snapshot state, so the paging check
 *    costs nothing while scrolling.
 *  - Shimmer skeletons reuse the exact card geometry of real cards.
 *  - One immutable `FeedUiState` per emission: only changed content
 *    recomposes (models are @Immutable with persistent lists).
 */
@Composable
fun HomeScreen(
    onVideoClick: (String) -> Unit,
    onSearchClick: () -> Unit
) {
    val container = LocalAppContainer.current
    val vm: HomeViewModel = viewModel(
        factory = viewModelFactory { initializer { HomeViewModel(container) } }
    )
    val state by vm.state.collectAsStateWithLifecycle()

    val listState = rememberLazyListState()
    val nearEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 4 && lastVisible >= info.totalItemsCount - 5
        }
    }
    LaunchedEffect(nearEnd) {
        if (nearEnd) vm.loadMore()
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        HomeTopBar(onSearchClick = onSearchClick)

        when {
            state.isLoadingInitial -> HomeSkeleton()

            state.error != null -> FeedMessage(
                message = state.error ?: "",
                actionLabel = "Retry",
                onAction = { vm.refresh() }
            )

            state.items.isEmpty() -> FeedMessage(message = "Nothing here right now. Pull back later.", actionLabel = "Retry", onAction = { vm.refresh() })

            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 4.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(
                    items = state.items,
                    key = { it.id },
                    contentType = { "video-card" }
                ) { video: VideoItem ->
                    VideoCard(video = video, onClick = onVideoClick)
                }
                if (state.isLoadingMore) {
                    item(key = "footer-loader", contentType = "loader") {
                        Box(
                            Modifier.fillMaxWidth().padding(14.dp),
                            contentAlignment = Alignment.Center
                        ) { CircularProgressIndicator(Modifier.size(22.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeTopBar(onSearchClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Brand mark: red play tile + wordmark
        Box(
            Modifier
                .size(26.dp)
                .clip(RoundedCornerShape(7.dp))
                .painterIcon()
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "You-Tube",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onSearchClick) {
            Icon(Icons.Filled.Search, contentDescription = "Search")
        }
    }
}

// Draws the splash play-mark drawable as the top-bar brand tile.
private fun Modifier.painterIcon(): Modifier = this.then(
    Modifier.paint(
        androidx.compose.ui.res.painterResource(R.drawable.ic_splash_logo)
    )
)

@Composable
private fun HomeSkeleton(cards: Int = 4) {
    Column(Modifier.fillMaxSize()) {
        repeat(cards) { ShimmerVideoCard(Modifier.padding(bottom = 14.dp)) }
    }
}

@Composable
fun FeedMessage(
    message: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(16.dp))
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}
