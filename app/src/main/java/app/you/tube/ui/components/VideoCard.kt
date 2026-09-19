package app.you.tube.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.you.tube.core.model.VideoItem

/** Duration / LIVE chip overlaid on thumbnails. */
@Composable
private fun DurationChip(text: String) {
    Box(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xCC000000))
            .padding(horizontal = 4.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 11.sp,
            lineHeight = 12.sp,
            fontWeight = FontWeight.W500
        )
    }
}

/** Full-width feed card (home / search results). */
@Composable
fun VideoCard(
    video: VideoItem,
    onClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = { onClick(video.id) })
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .padding(horizontal = 12.dp)
                .clip(RoundedCornerShape(12.dp))
        ) {
            ThumbImage(
                url = video.thumbnailUrl,
                contentDescription = video.title,
                modifier = Modifier.fillMaxSize()
            )
            Box(Modifier.align(Alignment.BottomEnd).padding(6.dp)) {
                when {
                    video.isLive -> DurationChip("LIVE")
                    video.isShort -> DurationChip("SHORTS")
                    video.duration != null -> DurationChip(video.duration)
                }
            }
        }
        Row(Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 12.dp)) {
            Spacer(Modifier.width(2.dp))
            Column(Modifier.fillMaxWidth()) {
                Text(
                    text = video.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.W500,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                val meta = buildString {
                    if (video.channelName.isNotBlank()) append(video.channelName)
                    video.views?.let { if (isNotEmpty()) append(" • "); append(it) }
                    video.published?.let { if (isNotEmpty()) append(" • "); append(it) }
                }
                if (meta.isNotEmpty()) {
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}

/** Compact row card (related videos / comments "up next" lists). */
@Composable
fun CompactVideoRow(
    video: VideoItem,
    onClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = { onClick(video.id) })
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Box(
            Modifier
                .fillMaxWidth(0.42f)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(8.dp))
        ) {
            ThumbImage(
                url = video.thumbnailUrl,
                contentDescription = video.title,
                modifier = Modifier.fillMaxSize()
            )
            Box(Modifier.align(Alignment.BottomEnd).padding(4.dp)) {
                when {
                    video.isLive -> DurationChip("LIVE")
                    video.duration != null -> DurationChip(video.duration)
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .align(Alignment.Top)
        ) {
            Text(
                text = video.title,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.W500,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = video.channelName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp)
            )
            val meta = listOfNotNull(video.views, video.published).joinToString(" • ")
            if (meta.isNotEmpty()) {
                Text(
                    text = meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

/** Grid card for the subscriptions feed (2-column grid like the YouTube app). */
@Composable
fun VideoGridCard(
    video: VideoItem,
    onClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = { onClick(video.id) })
            .padding(horizontal = 5.dp, vertical = 8.dp)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(10.dp))
        ) {
            ThumbImage(
                url = video.thumbnailUrl,
                contentDescription = video.title,
                modifier = Modifier.fillMaxSize()
            )
            Box(Modifier.align(Alignment.BottomEnd).padding(4.dp)) {
                when {
                    video.isLive -> DurationChip("LIVE")
                    video.duration != null -> DurationChip(video.duration)
                }
            }
        }
        Text(
            text = video.title,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.W500,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp)
        )
        val meta = listOfNotNull(video.channelName.takeIf { it.isNotBlank() }, video.views).joinToString(" • ")
        if (meta.isNotEmpty()) {
            Text(
                text = meta,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp)
            )
        }
    }
}
