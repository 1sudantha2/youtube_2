package app.you.tube.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.you.tube.core.model.ChannelItem
import app.you.tube.core.model.VideoItem
import app.you.tube.ui.LocalAppContainer
import app.you.tube.ui.components.AvatarImage
import app.you.tube.ui.components.ShimmerVideoCard
import app.you.tube.ui.components.VideoCard
import app.you.tube.ui.home.FeedMessage

@Composable
fun SearchScreen(
    onVideoClick: (String) -> Unit,
    onBack: () -> Unit
) {
    val container = LocalAppContainer.current
    val vm: SearchViewModel = viewModel(
        factory = viewModelFactory { initializer { SearchViewModel(container) } }
    )
    val state by vm.state.collectAsStateWithLifecycle()

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
        Row(
            Modifier.fillMaxWidth().padding(end = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            OutlinedTextField(
                value = state.query,
                onValueChange = vm::updateQuery,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search You-Tube") },
                singleLine = true,
                shape = MaterialTheme.shapes.extraLarge,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.search() })
            )
        }

        when {
            state.isLoading -> Column { repeat(3) { ShimmerVideoCard(Modifier.padding(bottom = 12.dp)) } }

            !state.searched -> SuggestionList(
                suggestions = state.suggestions,
                onPick = { vm.search(it) }
            )

            state.error != null -> FeedMessage(
                message = state.error ?: "Search failed",
                actionLabel = "Retry",
                onAction = { vm.search() }
            )

            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 4.dp, bottom = 20.dp)
            ) {
                if (state.channels.isNotEmpty()) {
                    items(
                        items = state.channels,
                        key = { "channel-" + it.id },
                        contentType = { "channel" }
                    ) { channel: ChannelItem ->
                        ChannelRow(channel)
                    }
                }
                items(
                    items = state.results,
                    key = { it.id },
                    contentType = { "video-card" }
                ) { video: VideoItem ->
                    VideoCard(video = video, onClick = onVideoClick)
                }
                if (state.isLoadingMore) {
                    item(key = "search-loader", contentType = "loader") {
                        Row(
                            Modifier.fillMaxWidth().padding(14.dp),
                            horizontalArrangement = Arrangement.Center
                        ) { CircularProgressIndicator(Modifier.size(22.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SuggestionList(suggestions: List<String>, onPick: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(items = suggestions, key = { it }, contentType = { "suggestion" }) { s ->
            Text(
                text = s,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(s) }
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            )
        }
    }
}

@Composable
private fun ChannelRow(channel: ChannelItem) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AvatarImage(
            url = channel.avatarUrl,
            contentDescription = channel.name,
            modifier = Modifier.size(48.dp)
        )
        Spacer(Modifier.width(14.dp))
        Column {
            Text(channel.name, style = MaterialTheme.typography.bodyMedium)
            channel.subscribers?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
