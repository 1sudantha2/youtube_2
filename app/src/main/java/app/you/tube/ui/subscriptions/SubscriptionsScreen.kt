package app.you.tube.ui.subscriptions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.you.tube.core.model.VideoItem
import app.you.tube.ui.LocalAppContainer
import app.you.tube.ui.components.ShimmerVideoCard
import app.you.tube.ui.components.VideoGridCard
import app.you.tube.ui.home.FeedMessage

/**
 * Subscriptions — 2-column grid (YouTube-app layout). Grid items keep the
 * same key + contentType recycling discipline as the feed lists.
 */
@Composable
fun SubscriptionsScreen(
    onVideoClick: (String) -> Unit,
    onSignIn: () -> Unit
) {
    val container = LocalAppContainer.current
    val vm: SubscriptionsViewModel = viewModel(
        factory = viewModelFactory { initializer { SubscriptionsViewModel(container) } }
    )
    val state by vm.state.collectAsStateWithLifecycle()

    val gridState = rememberLazyGridState()
    val nearEnd by remember {
        derivedStateOf {
            val info = gridState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 4 && last >= info.totalItemsCount - 6
        }
    }
    LaunchedEffect(nearEnd) { if (nearEnd) vm.loadMore() }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        Text(
            text = "Subscriptions",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 8.dp)
        )

        when {
            state.requireAuth -> SignInPrompt(onSignIn = onSignIn)

            state.isLoadingInitial -> Column(Modifier.fillMaxSize()) {
                repeat(3) { ShimmerVideoCard(Modifier.padding(bottom = 12.dp)) }
            }

            state.error != null -> FeedMessage(
                message = state.error ?: "",
                actionLabel = "Retry",
                onAction = { vm.refresh() }
            )

            state.items.isEmpty() -> FeedMessage(message = "No subscriptions to show yet.")

            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(
                    items = state.items,
                    key = { it.id },
                    contentType = { "video-grid-card" }
                ) { video: VideoItem ->
                    VideoGridCard(video = video, onClick = onVideoClick)
                }
                if (state.isLoadingMore) {
                    item(
                        key = "subs-loader",
                        contentType = "loader",
                        span = { GridItemSpan(2) }
                    ) {
                        Box(
                            Modifier.fillMaxWidth().padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) { CircularProgressIndicator() }
                    }
                }
            }
        }
    }
}

@Composable
private fun SignInPrompt(onSignIn: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .clip(RoundedCornerShape(14.dp))
    ) {
        Column(
            Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "Don't miss new videos",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "Sign in to see updates from channels you follow.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp, bottom = 14.dp)
            )
            Button(onClick = onSignIn) { Text("Sign in") }
        }
    }
}
