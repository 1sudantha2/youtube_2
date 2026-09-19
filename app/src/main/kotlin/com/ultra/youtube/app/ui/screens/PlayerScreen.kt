package com.ultra.youtube.app.ui.screens

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Dislike
import androidx.compose.material.icons.filled.Like
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ultra.youtube.app.player.LowRamPlayer
import com.ultra.youtube.app.player.VideoSurface
import com.ultra.youtube.app.ui.WatchViewModel
import com.ultra.youtube.app.ui.components.formatDuration
import com.ultra.youtube.app.ui.theme.AppColors
import kotlinx.coroutines.delay

/**
 * The watch screen.
 *
 * Structure:
 * ```
 * ┌ VideoSurface (SurfaceView, 16:9) ─ overlay controls ─┐
 * ├ title / channel / actions ────────────────────────────┤
 * ├ comments (verticalScroll, paged) ─────────────────────┤
 * └ quality bottom sheet (144p … 1080p / Auto) ───────────┘
 * ```
 *
 * The player is created here and released on dispose; the position is polled at 4 Hz while
 * playing rather than every frame, which is plenty for a scrubber and avoids a 60/120 Hz
 * recomposition loop.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.media3.common.util.UnstableApi::class)
@Composable
fun PlayerScreen(
    viewModel: WatchViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    player: LowRamPlayer? = null,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val lowRamPlayer = remember { player ?: LowRamPlayer.create(context) }
    DisposableEffect(Unit) {
        onDispose { if (player == null) lowRamPlayer.release() }
    }

    val playerState by lowRamPlayer.state.collectAsStateWithLifecycle()
    var showQualitySheet by remember { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(true) }

    // Kick off playback once metadata (and therefore the stream URLs) is available.
    LaunchedEffect(state.detail?.video?.id) {
        val detail = state.detail ?: return@LaunchedEffect
        lowRamPlayer.play(detail)
    }

    // Background audio: drop the video track when the screen is not visible, so the
    // decoder and its output buffers are freed instead of decoding into nothing.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> lowRamPlayer.setBackgroundAudioOnly(true)
                Lifecycle.Event.ON_RESUME -> lowRamPlayer.setBackgroundAudioOnly(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            lowRamPlayer.setBackgroundAudioOnly(false)
        }
    }

    // 4 Hz position sampling: enough for a scrubber, far cheaper than per-frame reads.
    LaunchedEffect(playerState.isPlaying) {
        while (playerState.isPlaying) {
            delay(250)
            lowRamPlayer.refreshPosition()
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(Color.Black),
        ) {
            VideoSurface(
                player = lowRamPlayer,
                state = playerState,
                modifier = Modifier.fillMaxSize(),
            )

            if (playerState.isBuffering) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(36.dp),
                    strokeWidth = 3.dp,
                    color = Color.White,
                )
            }

            if (showControls) {
                PlayerOverlay(
                    isPlaying = playerState.isPlaying,
                    positionMs = playerState.positionMs,
                    durationMs = playerState.durationMs,
                    qualityLabel = playerState.qualityLabel,
                    onTogglePlay = { lowRamPlayer.togglePlayPause() },
                    onSeek = { lowRamPlayer.seekTo(it) },
                    onOpenQuality = { showQualitySheet = true },
                    onBack = onBack,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(AppColors.Scrim)
                        .clickable { showControls = false },
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable { showControls = true },
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            val detail = state.detail
            if (detail != null) {
                Text(
                    text = detail.video.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(16.dp),
                )
                Text(
                    text = listOf(detail.video.channelName, detail.viewCountText)
                        .filter { it.isNotBlank() }
                        .joinToString(" • "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )

                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    ActionIcon(
                        label = detail.likeCountText,
                        active = state.isLiked,
                        icon = Icons.Default.Like,
                        onClick = viewModel::like,
                    )
                    ActionIcon(
                        label = "Dislike",
                        active = state.isDisliked,
                        icon = Icons.Default.Dislike,
                        onClick = viewModel::dislike,
                    )
                    ActionIcon(
                        label = if (state.isSubscribed) "Subscribed" else "Subscribe",
                        active = state.isSubscribed,
                        icon = Icons.Default.Settings,
                        onClick = viewModel::toggleSubscribe,
                    )
                }

                state.actionMessage?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }

                if (detail.description.isNotBlank()) {
                    Text(
                        text = detail.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }

                CommentList(
                    comments = state.comments,
                    isLoading = state.isLoadingComments,
                    onPost = viewModel::postComment,
                )
            } else if (state.error != null) {
                Text(
                    text = state.error.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }

    if (showQualitySheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showQualitySheet = false },
            sheetState = sheetState,
        ) {
            QualitySheet(
                heights = remember(lowRamPlayer) { lowRamPlayer.availableHeights() },
                isAuto = playerState.isAutoQuality,
                selectedHeight = playerState.selectedHeight,
                onAuto = {
                    lowRamPlayer.selectAutoQuality()
                    showQualitySheet = false
                },
                onSelect = { height ->
                    lowRamPlayer.selectQuality(height)
                    showQualitySheet = false
                },
            )
        }
    }
}

@Composable
private fun PlayerOverlay(
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    qualityLabel: String,
    onTogglePlay: () -> Unit,
    onSeek: (Long) -> Unit,
    onOpenQuality: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.systemBarsPadding()) {
        IconButton(onClick = onBack, modifier = Modifier.align(Alignment.TopStart)) {
            Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
        }

        IconButton(onClick = onTogglePlay, modifier = Modifier.align(Alignment.Center)) {
            Icon(
                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                tint = Color.White,
                modifier = Modifier.size(56.dp),
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
        ) {
            Slider(
                value = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f,
                onValueChange = { fraction -> onSeek((fraction * durationMs).toLong()) },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${formatMillis(positionMs)} / ${formatMillis(durationMs)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = qualityLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(AppColors.Badge)
                        .clickable(onClick = onOpenQuality)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun QualitySheet(
    heights: List<Int>,
    isAuto: Boolean,
    selectedHeight: Int,
    onAuto: () -> Unit,
    onSelect: (Int) -> Unit,
) {
    Column(modifier = Modifier.padding(bottom = 24.dp)) {
        Text(
            text = "Quality",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(16.dp),
        )
        QualityRow(label = "Auto", selected = isAuto, onClick = onAuto)
        // 1080p down to 144p; only the heights this video actually offers are listed.
        heights.forEach { height ->
            QualityRow(
                label = "${height}p",
                selected = !isAuto && selectedHeight == height,
                onClick = { onSelect(height) },
            )
        }
        if (heights.isEmpty()) {
            Text(
                text = "No adaptive renditions — playing progressive.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

@Composable
private fun QualityRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) AppColors.YouTubeRed else MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.weight(1f))
        if (selected) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(AppColors.YouTubeRed),
            )
        }
    }
}

@Composable
private fun ActionIcon(
    label: String,
    active: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (active) AppColors.YouTubeRed else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** `65_000` -> `1:05`. */
private fun formatMillis(ms: Long): String = formatDuration((ms / 1000L).toInt())
