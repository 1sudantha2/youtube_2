package io.github.sudantha.youtubelite.ui

import android.view.LayoutInflater
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import io.github.sudantha.youtubelite.R
import io.github.sudantha.youtubelite.player.PlaybackState

@Composable
fun WatchScreen(state: WatchState, playback: PlaybackState, player: Player?, signedIn: Boolean, model: MainViewModel, modifier: Modifier = Modifier) {
    var qualityDialog by remember { mutableStateOf(false) }
    var commentsTab by rememberSaveable(state.video?.id) { mutableStateOf(false) }
    var draft by rememberSaveable(state.video?.id) { mutableStateOf("") }
    Column(modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9)) {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
                (LayoutInflater.from(context).inflate(R.layout.player_surface, null, false) as PlayerView).apply {
                    this.player = player
                }
            }, update = { view -> view.player = player }, onRelease = { it.player = null })
            if (playback.resolving) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "details", contentType = "details") {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(state.video?.title.orEmpty(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(state.video?.channel.orEmpty(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick = { model.rate(true) }, enabled = signedIn && !state.actionPending) { Text("Like") }
                        TextButton(onClick = { model.rate(false) }, enabled = signedIn && !state.actionPending) { Text("Dislike") }
                        TextButton(onClick = { qualityDialog = true }, enabled = playback.qualities.isNotEmpty()) { Text("Quality") }
                    }
                    OutlinedButton(onClick = model::subscribe, enabled = signedIn && !state.actionPending && state.video?.channelId?.isNotBlank() == true) {
                        Text(if (state.subscribed == true) "Unsubscribe" else "Subscribe")
                    }
                    if (!signedIn) Text("Sign in to like, subscribe and comment.", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (playback.error != null) item(key = "playback-error", contentType = "status") {
                StatusCard(playback.error, "Retry playback", model::retryPlayback)
            }
            if (playback.notice != null) item(key = "notice", contentType = "status") { StatusCard(playback.notice) }
            if (state.message != null) item(key = "message", contentType = "status") { StatusCard(state.message) }
            item(key = "tabs", contentType = "tabs") {
                TabRow(selectedTabIndex = if (commentsTab) 1 else 0) {
                    Tab(selected = !commentsTab, onClick = { commentsTab = false }, text = { Text("Up next") })
                    Tab(selected = commentsTab, onClick = {
                        commentsTab = true
                        if (state.comments.isEmpty()) model.loadComments()
                    }, text = { Text("Comments") })
                }
                Spacer(Modifier.height(16.dp))
            }
            if (commentsTab) {
                if (signedIn && state.commentParams != null) item(key = "composer", contentType = "composer") {
                    Column(Modifier.padding(16.dp)) {
                        OutlinedTextField(value = draft, onValueChange = { if (it.length <= 10000) draft = it },
                            label = { Text("Add a comment") }, modifier = Modifier.fillMaxWidth(), maxLines = 5)
                        TextButton(onClick = { model.comment(draft) }, enabled = draft.isNotBlank() && !state.actionPending) { Text("Post comment") }
                    }
                }
                items(state.comments, key = { it.id }, contentType = { "comment" }) { comment ->
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(comment.author, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text(comment.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
                        if (comment.likes.isNotEmpty()) Text("${comment.likes} likes", style = MaterialTheme.typography.bodySmall)
                    }
                }
                item(key = "comment-status", contentType = "status") {
                    when {
                        state.commentsLoading || state.loading -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
                        state.commentsToken != null -> TextButton(onClick = model::loadComments, modifier = Modifier.padding(16.dp)) { Text("Load comments") }
                        state.comments.isEmpty() -> StatusCard("Comments are disabled or unavailable for this video.")
                    }
                }
            } else {
                items(state.related, key = { it.id }, contentType = { "video" }) { VideoCard(it, model::open) }
                item(key = "related-status", contentType = "status") {
                    when {
                        state.loading || state.relatedLoading -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
                        state.relatedToken != null -> TextButton(onClick = model::loadRelated, modifier = Modifier.padding(16.dp)) { Text("More videos") }
                        state.related.isEmpty() -> StatusCard("No recommendations available.")
                    }
                }
            }
        }
    }
    if (qualityDialog) AlertDialog(onDismissRequest = { qualityDialog = false }, title = { Text("Playback quality") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                TextButton(onClick = { model.engine.quality(null); qualityDialog = false }) { Text("Auto · up to 720p, low-data fallback") }
                playback.qualities.forEach { option ->
                    TextButton(onClick = { model.engine.quality(option.id); qualityDialog = false }) {
                        Text("${option.height}p${if (option.videoOnly) " · separate audio" else " · combined"}${if (playback.selected == option.id) " ✓" else ""}")
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { qualityDialog = false }) { Text("Done") } })
}
