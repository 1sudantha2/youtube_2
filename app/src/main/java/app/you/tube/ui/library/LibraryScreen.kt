package app.you.tube.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
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
import app.you.tube.ui.components.VideoCard
import app.you.tube.ui.home.FeedMessage

@Composable
fun LibraryScreen(
    onVideoClick: (String) -> Unit,
    onSignIn: () -> Unit
) {
    val container = LocalAppContainer.current
    val vm: LibraryViewModel = viewModel(
        factory = viewModelFactory { initializer { LibraryViewModel(container) } }
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val section by vm.section.collectAsStateWithLifecycle()

    val listState = rememberLazyListState()
    val nearEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 4 && last >= info.totalItemsCount - 5
        }
    }
    LaunchedEffect(nearEnd) { if (nearEnd) vm.loadMore() }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        Text(
            text = "You",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 16.dp, top = 10.dp)
        )
        androidx.compose.foundation.layout.Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            LibrarySection.entries.forEach { s ->
                FilterChip(
                    selected = section == s,
                    onClick = { vm.select(s) },
                    label = { Text(s.title) }
                )
            }
        }

        when {
            state.requireAuth -> LibrarySignInPrompt(onSignIn = onSignIn)

            state.isLoadingInitial -> Column(Modifier.fillMaxSize()) {
                repeat(3) { ShimmerVideoCard(Modifier.padding(bottom = 12.dp)) }
            }

            state.error != null -> FeedMessage(
                message = state.error ?: "",
                actionLabel = "Retry",
                onAction = { vm.refresh() }
            )

            state.items.isEmpty() -> FeedMessage(message = "Nothing in ${section.title.lowercase()} yet.")

            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 4.dp, bottom = 20.dp)
            ) {
                items(
                    items = state.items,
                    key = { it.id },
                    contentType = { "video-card" }
                ) { video: VideoItem ->
                    VideoCard(video = video, onClick = onVideoClick)
                }
                if (state.isLoadingMore) {
                    item(key = "library-loader", contentType = "loader") {
                        androidx.compose.foundation.layout.Box(
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
private fun LibrarySignInPrompt(onSignIn: () -> Unit) {
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
                "Your library lives with your account",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "Sign in to see your history, liked videos and watch later list.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp, bottom = 14.dp)
            )
            Button(onClick = onSignIn) { Text("Sign in") }
        }
    }
}
