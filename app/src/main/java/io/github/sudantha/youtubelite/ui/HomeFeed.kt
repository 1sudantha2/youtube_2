package io.github.sudantha.youtubelite.ui

import android.graphics.Bitmap
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Precision
import io.github.sudantha.youtubelite.data.FeedState
import io.github.sudantha.youtubelite.data.Video

@Composable
fun HomeFeed(state: FeedState, onVideo: (Video) -> Unit, onRetry: () -> Unit, onMore: () -> Unit, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    // No offset/position is read during composition. This infrequent derived flag only drives a button.
    val showTop by remember { derivedStateOf { listState.firstVisibleItemIndex > 8 } }
    LaunchedEffect(state.feed, state.query) { listState.scrollToItem(0) }
    LazyColumn(state = listState, modifier = modifier.testTag("home_feed"), contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "heading", contentType = "heading") {
            Column(Modifier.padding(16.dp)) {
                Text(if (state.query.isBlank()) state.feed.label else "Results for “${state.query}”",
                    style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(if (showTop) "Your next watch is waiting" else "Less overhead. More video.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (state.loading) {
            items(3, key = { "skeleton-$it" }, contentType = { "skeleton" }) { ShimmerCard() }
        } else {
            items(state.videos, key = { it.id }, contentType = { "video" }) { video -> VideoCard(video, onVideo) }
            if (state.error != null) item(key = "error", contentType = "status") {
                StatusCard(state.error, "Retry", onRetry)
            }
            if (state.videos.isEmpty() && state.error == null) item(key = "empty", contentType = "status") {
                StatusCard("Nothing here yet. Try searching for a video, or sign in for your personal feed.", "Refresh", onRetry)
            }
            if (state.continuation != null) item(key = "more", contentType = "status") {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    if (state.loadingMore) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    else OutlinedButton(onClick = onMore) { Text("Load more") }
                }
            }
        }
    }
}

@Composable
fun VideoCard(video: Video, onVideo: (Video) -> Unit) {
    Column(Modifier.fillMaxWidth().clickable { onVideo(video) }.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(16f / 9).clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)) {
            val density = LocalDensity.current
            val width = with(density) { maxWidth.roundToPx() }.coerceAtLeast(1)
            val height = with(density) { maxHeight.roundToPx() }.coerceAtLeast(1)
            val context = LocalContext.current
            val image = remember(video.thumbnail, width, height, context) {
                ImageRequest.Builder(context).data(video.thumbnail).size(width, height)
                    .precision(Precision.EXACT).bitmapConfig(Bitmap.Config.HARDWARE).allowHardware(true)
                    .crossfade(false).build()
            }
            AsyncImage(model = image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            if (video.duration.isNotBlank()) Text(video.duration, style = MaterialTheme.typography.labelSmall,
                color = Color.White, modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)
                    .clip(RoundedCornerShape(4.dp)).background(Color.Black.copy(alpha = .8f)).padding(horizontal = 5.dp, vertical = 2.dp))
        }
        Text(video.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 10.dp))
        Text(listOf(video.channel, video.views).filter(String::isNotBlank).joinToString(" · "),
            maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
fun ShimmerCard() {
    val animation = rememberInfiniteTransition(label = "skeleton")
    val phase = animation.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "shimmer")
    val base = MaterialTheme.colorScheme.surfaceVariant
    // Reading phase.value inside drawing invalidates draw only, NOT composition or layout.
    val shimmer = Modifier.drawWithContent {
        drawContent()
        val x = size.width * (phase.value * 3 - 1)
        drawRect(Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = .07f), Color.Transparent),
            start = Offset(x, 0f), end = Offset(x + size.width * .6f, size.height)))
    }
    Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9).clip(RoundedCornerShape(12.dp)).background(base).then(shimmer))
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth(.85f).height(18.dp).clip(RoundedCornerShape(4.dp)).background(base).then(shimmer))
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth(.45f).height(12.dp).clip(RoundedCornerShape(4.dp)).background(base).then(shimmer))
    }
}

@Composable
fun StatusCard(message: String, action: String? = null, onAction: () -> Unit = {}) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp), modifier = Modifier.padding(16.dp).fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(message, style = MaterialTheme.typography.bodyMedium)
            if (action != null) TextButton(onClick = onAction) { Text(action) }
        }
    }
}
