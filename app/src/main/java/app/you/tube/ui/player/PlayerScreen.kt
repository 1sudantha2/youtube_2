package app.you.tube.ui.player

import android.content.Intent
import android.view.SurfaceView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.media3.common.Player
import app.you.tube.R
import app.you.tube.core.model.CommentItem
import app.you.tube.core.model.LikeState
import app.you.tube.core.model.StreamBundle
import app.you.tube.core.model.StreamQuality
import app.you.tube.core.model.VideoItem
import app.you.tube.core.util.Formats
import app.you.tube.player.PlayerViewModel
import app.you.tube.ui.LocalAppContainer
import app.you.tube.ui.components.AvatarImage
import app.you.tube.ui.components.ShimmerCompactCard
import app.you.tube.ui.components.CompactVideoRow
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.delay

/**
 * Watch screen.
 *
 * Rendering pipeline highlights:
 *  - Video renders into a raw [SurfaceView] attached to the session player
 *    through the MediaController. SurfaceView composites straight from the
 *    hardware decoder via SurfaceFlinger — no TextureView GPU readback, saving
 *    GPU memory/battery and eliminating a full-frame copy per frame.
 *  - Position/duration updates are produced by a 500 ms polling loop writing
 *    into state that is read only by the tiny TimeBar composable — the rest
 *    of the screen never recomposes during playback.
 *  - On ON_STOP the video track is disabled (decoder released) and playback
 *    continues audio-only in the foreground service; ON_START re-enables it.
 */
@Composable
fun PlayerScreen(
    videoId: String,
    onBack: () -> Unit,
    onOpenVideo: (String) -> Unit
) {
    val container = LocalAppContainer.current
    val vm: PlayerViewModel = viewModel(
        key = "player_$videoId",
        factory = viewModelFactory { initializer { PlayerViewModel(videoId, container) } }
    )
    val uiState by vm.uiState.collectAsStateWithLifecycle()
    val controller by vm.controller.collectAsStateWithLifecycle()
    val isPlaying by vm.isPlaying.collectAsStateWithLifecycle()
    val playbackState by vm.playbackState.collectAsStateWithLifecycle()
    val aspectRatio by vm.videoAspectRatio.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(vm) {
        vm.events.collect { snackbarHostState.showSnackbar(it) }
    }

    // Hold the screen on only while actually playing (battery-friendly).
    val view = LocalView.current
    DisposableEffect(isPlaying) {
        view.keepScreenOn = isPlaying
        onDispose { view.keepScreenOn = false }
    }

    // Background <-> foreground: audio-only when backgrounded.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> vm.setVideoTrackEnabled(false)
                Lifecycle.Event.ON_START -> vm.setVideoTrackEnabled(true)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var showQualitySheet by remember { mutableStateOf(false) }
    var showComments by remember { mutableStateOf(false) }

    val bundle = uiState.bundle
    val selectedQuality = bundle?.qualityByItag(uiState.selectedQualityItag)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(bottom = padding.calculateBottomPadding())
        ) {
            VideoSurfaceBox(
                controller = controller,
                aspectRatio = aspectRatio,
                isPlaying = isPlaying,
                isBuffering = playbackState == Player.STATE_BUFFERING,
                isLive = bundle?.isLive == true,
                qualityLabel = selectedQuality?.label ?: "Auto",
                onBack = onBack,
                onTogglePlayPause = vm::togglePlayPause,
                onQualityClick = { showQualitySheet = true }
            )

            when {
                uiState.isLoading -> Column(Modifier.fillMaxSize()) {
                    repeat(4) { ShimmerCompactCard(Modifier.padding(vertical = 4.dp)) }
                }

                uiState.error != null -> Column(
                    Modifier.fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        uiState.error ?: "Something went wrong",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onBack) { Text("Go back") }
                }

                bundle != null -> VideoDetails(
                    bundle = bundle,
                    likeState = uiState.likeState,
                    isSubscribed = uiState.isSubscribed,
                    onLike = vm::toggleLike,
                    onDislike = vm::toggleDislike,
                    onSubscribe = vm::toggleSubscribe,
                    onOpenComments = { showComments = true },
                    onOpenVideo = onOpenVideo
                )
            }
        }
    }

    if (showQualitySheet && bundle != null) {
        QualitySheet(
            bundle = bundle,
            selectedItag = uiState.selectedQualityItag,
            onDismiss = { showQualitySheet = false },
            onSelect = { q ->
                showQualitySheet = false
                vm.selectQuality(q)
            }
        )
    }

    if (showComments) {
        LaunchedEffect(Unit) { vm.ensureCommentsLoaded() }
        CommentsSheet(
            vm = vm,
            onDismiss = { showComments = false },
            onSignIn = { showComments = false }
        )
    }
}

// --------------------------------------------------------------- video box

@Composable
private fun VideoSurfaceBox(
    controller: Player?,
    aspectRatio: Float,
    isPlaying: Boolean,
    isBuffering: Boolean,
    isLive: Boolean,
    qualityLabel: String,
    onBack: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onQualityClick: () -> Unit
) {
    val context = LocalContext.current
    val surfaceView = remember { SurfaceView(context) }
    var showControls by remember { mutableStateOf(true) }

    // Auto-hide controls while playing.
    LaunchedEffect(showControls, isPlaying) {
        if (showControls && isPlaying) {
            delay(3500)
            showControls = false
        }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(aspectRatio)
            .background(Color.Black)
    ) {
        AndroidViewSurface(surfaceView = surfaceView)

        DisposableEffect(controller) {
            controller?.setVideoSurfaceView(surfaceView)
            onDispose { controller?.clearVideoSurfaceView(surfaceView) }
        }

        // Tap layer toggles controls (no ripple).
        Box(
            Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { showControls = !showControls }
        )

        if (isBuffering && !isLive) {
            CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 2.5.dp,
                modifier = Modifier.align(Alignment.Center).size(34.dp)
            )
        }

        if (!isPlaying && !isLive && !isBuffering) {
            IconButton(
                onClick = onTogglePlayPause,
                modifier = Modifier.align(Alignment.Center).size(56.dp)
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = "Play",
                    tint = Color.White,
                    modifier = Modifier.size(44.dp)
                )
            }
        }

        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(animationSpec = tween(150)),
            exit = fadeOut(animationSpec = tween(200)),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.40f))) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.align(Alignment.TopStart).padding(4.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                }
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0x88000000),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable(onClick = onQualityClick)
                ) {
                    Row(
                        Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.Settings,
                            contentDescription = "Quality",
                            tint = Color.White,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(qualityLabel, color = Color.White, fontSize = 12.sp)
                    }
                }

                IconButton(
                    onClick = onTogglePlayPause,
                    modifier = Modifier.align(Alignment.Center).size(64.dp)
                ) {
                    val pauseIcon = ImageVector.vectorResource(R.drawable.ic_pause)
                    Icon(
                        if (isPlaying) pauseIcon else Icons.Filled.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = Color.White,
                        modifier = Modifier.size(48.dp)
                    )
                }

                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                    if (!isLive && controller != null) {
                        TimeBar(controller)
                    } else {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.End
                        ) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = Color(0xCCFF0033)
                            ) {
                                Text(
                                    "LIVE",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Thin indirection so AndroidView's factory captures the remembered SurfaceView. */
@Composable
private fun AndroidViewSurface(surfaceView: SurfaceView) {
    androidx.compose.ui.viewinterop.AndroidView(
        factory = { surfaceView },
        modifier = Modifier.fillMaxSize()
    )
}

/**
 * Self-contained progress bar. The 500 ms poll writes into long-valued state
 * read ONLY here — Compose can skip everything else in the screen.
 */
@Composable
private fun TimeBar(controller: Player) {
    val position = remember { mutableLongStateOf(0L) }
    val duration = remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(controller) {
        while (true) {
            if (!dragging) {
                position.value = controller.currentPosition.coerceAtLeast(0L)
                val d = controller.duration
                duration.value = if (d > 0) d else 0L
            }
            delay(500)
        }
    }

    val fraction = if (dragging) dragFraction
    else if (duration.value > 0) (position.value.toFloat() / duration.value).coerceIn(0f, 1f)
    else 0f

    Column(Modifier.fillMaxWidth()) {
        Slider(
            value = fraction,
            onValueChange = {
                dragging = true
                dragFraction = it
            },
            onValueChangeFinished = {
                if (duration.value > 0) {
                    controller.seekTo((dragFraction * duration.value).toLong())
                }
                dragging = false
            },
            valueRange = 0f..1f
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(Formats.durationMs(position.value), color = Color.White, fontSize = 12.sp)
            Text(Formats.durationMs(duration.value), color = Color.White, fontSize = 12.sp)
        }
    }
}

// ------------------------------------------------------------- details

@Composable
private fun VideoDetails(
    bundle: StreamBundle,
    likeState: LikeState,
    isSubscribed: Boolean,
    onLike: () -> Unit,
    onDislike: () -> Unit,
    onSubscribe: () -> Unit,
    onOpenComments: () -> Unit,
    onOpenVideo: (String) -> Unit
) {
    val context = LocalContext.current
    var descriptionExpanded by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Text(
            text = bundle.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp)
        )
        val meta = listOfNotNull(bundle.viewCountText, bundle.uploadDate).joinToString(" • ")
        if (meta.isNotEmpty()) {
            Text(
                text = meta,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 4.dp)
            )
        }

        // Channel row
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AvatarImage(
                url = bundle.channelAvatarUrl,
                contentDescription = bundle.channelName,
                modifier = Modifier.size(40.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    bundle.channelName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                bundle.subscriberCount?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Button(
                onClick = onSubscribe,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
            ) {
                Text(if (isSubscribed) "Subscribed" else "Subscribe", fontSize = 12.sp)
            }
        }

        // Action row
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ActionPill(
                selected = likeState == LikeState.LIKE,
                onClick = onLike
            ) {
                Icon(
                    Icons.Filled.ThumbUp,
                    contentDescription = "Like",
                    tint = if (likeState == LikeState.LIKE) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(17.dp)
                )
                bundle.likeCountText?.let {
                    Spacer(Modifier.width(6.dp))
                    Text(it, fontSize = 13.sp)
                }
            }
            ActionPill(selected = likeState == LikeState.DISLIKE, onClick = onDislike) {
                Icon(
                    ImageVector.vectorResource(R.drawable.ic_thumb_down),
                    contentDescription = "Dislike",
                    tint = if (likeState == LikeState.DISLIKE) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(17.dp)
                )
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onOpenComments) {
                Icon(
                    ImageVector.vectorResource(R.drawable.ic_comment),
                    contentDescription = "Comments",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            IconButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, "https://youtu.be/${bundle.videoId}")
                }
                context.startActivity(Intent.createChooser(send, "Share video"))
            }) {
                Icon(Icons.Filled.Share, "Share", tint = MaterialTheme.colorScheme.onSurface)
            }
        }

        // Description
        if (!bundle.description.isNullOrBlank()) {
            Text(
                text = bundle.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (descriptionExpanded) 24 else 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { descriptionExpanded = !descriptionExpanded }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            )
        }

        // Related videos
        Text(
            "Up next",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 14.dp, top = 6.dp, bottom = 2.dp)
        )
        LazyColumn(
            Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            items(
                items = bundle.related,
                key = { it.id },
                contentType = { "related-video" }
            ) { video: VideoItem ->
                CompactVideoRow(video = video, onClick = onOpenVideo)
            }
        }
    }
}

@Composable
private fun ActionPill(
    selected: Boolean,
    onClick: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = if (selected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )
    }
}

// ------------------------------------------------------------ quality sheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QualitySheet(
    bundle: StreamBundle,
    selectedItag: Int?,
    onDismiss: () -> Unit,
    onSelect: (StreamQuality) -> Unit
) {
    val qualities = remember(bundle) {
        (bundle.qualities + listOfNotNull(bundle.fallbackMuxed)).distinctBy { it.itag }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            "Video quality",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 18.dp, bottom = 6.dp)
        )
        LazyColumn(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp),
            contentPadding = PaddingValues(bottom = 22.dp)
        ) {
            items(
                items = qualities,
                key = { it.itag },
                contentType = { "quality" }
            ) { q ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(q) }
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = q.itag == selectedItag, onClick = null)
                    Text(
                        text = q.label + if (q.isMuxed) "  •  standard" else "",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = q.container.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 18.dp)
                    )
                }
            }
        }
    }
}
