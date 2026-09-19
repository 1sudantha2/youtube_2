package com.youtubelite.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.view.LayoutInflater
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Precision
import com.youtubelite.app.AppGraph
import com.youtubelite.app.R
import com.youtubelite.app.data.ActionsRepository
import com.youtubelite.app.data.CommentsRepository
import com.youtubelite.app.data.NotSignedInException
import com.youtubelite.app.extractor.StreamResolver
import com.youtubelite.app.innertube.Innertube
import com.youtubelite.app.innertube.YtParsers
import com.youtubelite.app.model.Comment
import com.youtubelite.app.model.StreamSet
import com.youtubelite.app.model.Video
import com.youtubelite.app.model.WatchMeta
import com.youtubelite.app.player.PlaybackHub
import com.youtubelite.app.ui.nav.NavState
import com.youtubelite.app.ui.nav.Screen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class WatchViewModel(private val videoId: String) : ViewModel() {

    private val _meta = MutableStateFlow<WatchMeta?>(null)
    val meta: StateFlow<WatchMeta?> = _meta

    private val _streams = MutableStateFlow<StreamSet?>(null)
    val streams: StateFlow<StreamSet?> = _streams

    private val _related = MutableStateFlow<List<Video>>(emptyList())
    val related: StateFlow<List<Video>> = _related

    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError

    private val _comments = MutableStateFlow<List<Comment>>(emptyList())
    val comments: StateFlow<List<Comment>> = _comments

    private val _commentsLoading = MutableStateFlow(false)
    val commentsLoading: StateFlow<Boolean> = _commentsLoading

    private val _commentsEnded = MutableStateFlow(true)
    val commentsEnded: StateFlow<Boolean> = _commentsEnded

    private val _liked = MutableStateFlow<Boolean?>(null)
    val liked: StateFlow<Boolean?> = _liked

    private val _disliked = MutableStateFlow(false)
    val disliked: StateFlow<Boolean> = _disliked

    private val _subscribed = MutableStateFlow(false)
    val subscribed: StateFlow<Boolean> = _subscribed

    private val _likeCount = MutableStateFlow("")
    val likeCount: StateFlow<String> = _likeCount

    private val _actionError = MutableStateFlow<String?>(null)
    val actionError: StateFlow<String?> = _actionError

    private var commentsToken: String? = null
    private var commentsInFlight = false
    private var playbackStarted = false

    init {
        load()
    }

    fun load() {
        if (_loadError.value != null && _meta.value != null) return
        _loadError.value = null
        viewModelScope.launch {
            try {
                val resolved = StreamResolver.resolve(videoId)
                _meta.value = resolved.meta
                _streams.value = resolved.streams
                _related.value = resolved.related
                _likeCount.value = resolved.meta.likes

                if (!playbackStarted) {
                    playbackStarted = true
                    if (PlaybackHub.ensureConnected()) {
                        PlaybackHub.play(resolved.meta, resolved.streams, auto = true)
                        val pref = AppGraph.prefs.defaultQuality.first()
                        if (pref != "auto") PlaybackHub.setQuality(pref)
                    } else {
                        _loadError.value = "Playback service unavailable"
                    }
                }

                try {
                    val nextRoot = Innertube.next(videoId = videoId)
                    _liked.value = YtParsers.findLikeState(nextRoot)
                    _subscribed.value = YtParsers.findSubscribedState(nextRoot)
                } catch (_: Throwable) {
                    Unit
                }

                if (_comments.value.isEmpty()) loadComments(first = true)
            } catch (t: Throwable) {
                _loadError.value = t.message ?: "Failed to load video"
            }
        }
    }

    fun loadComments(first: Boolean) {
        if (commentsInFlight) return
        if (!first && commentsToken == null) return
        viewModelScope.launch {
            commentsInFlight = true
            _commentsLoading.value = true
            try {
                val page = if (first) {
                    CommentsRepository.firstPage(videoId)
                } else {
                    CommentsRepository.nextPage(commentsToken ?: return@launch)
                }
                _comments.value = if (first) page.comments else _comments.value + page.comments
                commentsToken = page.nextToken
                _commentsEnded.value = page.nextToken == null
            } catch (_: Throwable) {
                if (first) _comments.value = emptyList()
                _commentsEnded.value = true
            } finally {
                _commentsLoading.value = false
                commentsInFlight = false
            }
        }
    }

    fun toggleLike() {
        val target = _liked.value != true
        viewModelScope.launch {
            try {
                if (target) ActionsRepository.like(videoId) else ActionsRepository.removeLike(videoId)
                _liked.value = if (target) true else null
            } catch (t: NotSignedInException) {
                _actionError.value = t.message
            } catch (t: Throwable) {
                _actionError.value = "Like failed"
            }
        }
    }

    fun toggleDislike() {
        val target = !_disliked.value
        viewModelScope.launch {
            try {
                if (target) {
                    ActionsRepository.dislike(videoId)
                    if (_liked.value == true) _liked.value = null
                } else {
                    ActionsRepository.removeLike(videoId)
                }
                _disliked.value = target
            } catch (t: NotSignedInException) {
                _actionError.value = t.message
            } catch (t: Throwable) {
                _actionError.value = "Action failed"
            }
        }
    }

    fun toggleSubscribe() {
        val target = !_subscribed.value
        viewModelScope.launch {
            try {
                val channelId = _meta.value?.channelId.orEmpty()
                if (channelId.isEmpty()) throw IllegalStateException("Channel unavailable")
                if (target) ActionsRepository.subscribe(channelId)
                else ActionsRepository.unsubscribe(channelId)
                _subscribed.value = target
            } catch (t: NotSignedInException) {
                _actionError.value = t.message
            } catch (t: Throwable) {
                _actionError.value = "Subscribe failed"
            }
        }
    }

    fun postComment(text: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            try {
                ActionsRepository.createComment(videoId, _meta.value?.channelId.orEmpty(), text)
                onResult(true)
                _comments.value = emptyList()
                commentsToken = null
                loadComments(first = true)
            } catch (t: NotSignedInException) {
                _actionError.value = t.message
                onResult(false)
            } catch (t: Throwable) {
                _actionError.value = "Comment failed"
                onResult(false)
            }
        }
    }

    fun clearActionError() {
        _actionError.value = null
    }

    companion object {
        fun factory(videoId: String) = viewModelFactory {
            initializer { WatchViewModel(videoId) }
        }
    }
}

@Composable
fun WatchScreen(videoId: String, nav: NavState) {
    val vm: WatchViewModel = viewModel(
        key = "watch_$videoId",
        factory = WatchViewModel.factory(videoId),
    )
    val meta by vm.meta.collectAsStateWithLifecycle()
    val streams by vm.streams.collectAsStateWithLifecycle()
    val related by vm.related.collectAsStateWithLifecycle()
    val loadError by vm.loadError.collectAsStateWithLifecycle()
    val liked by vm.liked.collectAsStateWithLifecycle()
    val disliked by vm.disliked.collectAsStateWithLifecycle()
    val subscribed by vm.subscribed.collectAsStateWithLifecycle()
    val likeCount by vm.likeCount.collectAsStateWithLifecycle()
    val actionError by vm.actionError.collectAsStateWithLifecycle()
    val activeLabel by PlaybackHub.activeLabel.collectAsStateWithLifecycle()
    val isAuto by PlaybackHub.isAuto.collectAsStateWithLifecycle()
    val audioOnly by PlaybackHub.audioOnly.collectAsStateWithLifecycle()

    val context = LocalContext.current

    // Media notification permission (Android 13+), requested once per screen entry.
    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Decoder release when app is backgrounded; surface re-attach on return.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> PlaybackHub.onBackgrounded()
                Lifecycle.Event.ON_RESUME -> PlaybackHub.onForegrounded()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            PlaybackHub.bindPlayerView(null)
            PlaybackHub.onBackgrounded()
        }
    }

    val actionErrorHandler = { err: String? ->
        if (!err.isNullOrEmpty()) {
            Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
            vm.clearActionError()
        }
        Unit
    }
    LaunchedEffect(actionError) { actionErrorHandler(actionError) }

    var showQuality by rememberSaveable { mutableStateOf(false) }
    var titleExpanded by rememberSaveable { mutableStateOf(false) }
    var descExpanded by rememberSaveable { mutableStateOf(false) }

    val openVideo = remember(nav) {
        { video: Video -> nav.push(Screen.Watch(video.id)) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // ---------------- Player: SurfaceView via PlayerView ---------------- //
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(Color.Black)
        ) {
            AndroidView(
                factory = { ctx ->
                    (LayoutInflater.from(ctx).inflate(R.layout.player_view, null, false)
                        as PlayerView)
                        .also { PlaybackHub.bindPlayerView(it) }
                },
                update = {},
                onRelease = { PlaybackHub.bindPlayerView(null) },
            )
            IconButton(
                onClick = { nav.pop() },
                modifier = Modifier.align(Alignment.TopStart),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White,
                )
            }
        }

        when {
            loadError != null && meta == null -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            loadError ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { vm.load() }) { Text("Retry") }
                    }
                }
            }

            meta == null -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                }
            }

            else -> {
                val m = meta ?: return
                LazyColumn(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    item(key = "info", contentType = "info") {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Text(
                                text = m.title,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = if (titleExpanded) 6 else 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.clickable { titleExpanded = !titleExpanded },
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = listOf(m.views, m.published)
                                    .filter { it.isNotBlank() }
                                    .joinToString("  •  "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(12.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = m.channel.take(1).uppercase(),
                                        color = Color.White,
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                }
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = m.channel,
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Button(onClick = { vm.toggleSubscribe() }) {
                                    Text(if (subscribed) "Subscribed" else "Subscribe")
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ActionChip(
                                    label = when (liked) {
                                        true -> "Liked"
                                        else -> likeCount.ifEmpty { "Like" }
                                    },
                                    icon = Icons.Filled.ThumbUp,
                                    active = liked == true,
                                    onClick = { vm.toggleLike() },
                                    modifier = Modifier.weight(1f),
                                )
                                ActionChip(
                                    label = if (disliked) "Disliked" else "Dislike",
                                    icon = Icons.Filled.ThumbDown,
                                    active = disliked,
                                    onClick = { vm.toggleDislike() },
                                    modifier = Modifier.weight(1f),
                                )
                                ActionChip(
                                    label = if (isAuto) "Auto" else activeLabel ?: "Auto",
                                    icon = Icons.Filled.Settings,
                                    active = false,
                                    onClick = { showQuality = true },
                                    modifier = Modifier.weight(1f),
                                )
                                ActionChip(
                                    label = if (audioOnly) "Audio ✓" else "Audio",
                                    icon = null,
                                    active = audioOnly,
                                    onClick = { PlaybackHub.setAudioOnly(!audioOnly) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            if (m.description.isNotBlank()) {
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    text = m.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = if (descExpanded) Int.MAX_VALUE else 3,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.clickable { descExpanded = !descExpanded },
                                )
                            }
                        }
                    }
                    item(key = "comments_entry", contentType = "row") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { nav.push(Screen.Comments(videoId)) }
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Comments",
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(Icons.Filled.ArrowForward, contentDescription = null)
                        }
                    }
                    item(key = "related_header", contentType = "row") {
                        Text(
                            text = "Up next",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                    }
                    items(
                        items = related,
                        key = { "rel_${it.id}" },
                        contentType = { "video" },
                    ) { video ->
                        VideoCard(video = video, onClick = openVideo)
                    }
                }
            }
        }
    }

    val s = streams
    if (showQuality && s != null) {
        QualitySheet(
            streams = s,
            activeLabel = activeLabel,
            isAuto = isAuto,
            audioOnly = audioOnly,
            onDismiss = { showQuality = false },
        )
    }
}

@Composable
private fun ActionChip(
    label: String,
    icon: ImageVector?,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = if (active) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surfaceContainer,
        contentColor = if (active) Color.White else MaterialTheme.colorScheme.onSurface,
        modifier = modifier.height(36.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(5.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
