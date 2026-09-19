package io.github.sudantha.youtubelite.player

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.media3.common.*
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import io.github.sudantha.youtubelite.data.Video
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient

@Immutable
data class PlaybackState(
    val video: Video? = null, val resolving: Boolean = false,
    val qualities: PersistentList<StreamOption> = persistentListOf(), val selected: String? = null,
    val audioOnly: Boolean = false, val error: String? = null, val notice: String? = null,
)

/** All player calls run on main; extraction/network never run on main. Owned by PlaybackService. */
class PlaybackEngine(private val context: Context, client: OkHttpClient) {
    private val resolver = StreamResolver(client)
    private val sourceFactory = DefaultMediaSourceFactory(OkHttpDataSource.Factory(client))
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var resolveJob: Job? = null
    private var fallbackJob: Job? = null
    private var streams: ResolvedStreams? = null
    private var selected: StreamOption? = null
    private var fallbackUsed = false
    private var videoVisible = true
    private var allowBackground = true
    private var selector: DefaultTrackSelector? = null
    private var instance: ExoPlayer? = null
    val player: ExoPlayer? get() = instance
    private val mutable = MutableStateFlow(PlaybackState())
    val state = mutable.asStateFlow()

    fun create(): ExoPlayer {
        instance?.let { return it }
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val tracks = DefaultTrackSelector(context).apply {
            setParameters(buildUponParameters().setMaxVideoSize(1920, 1080)
                .setPreferredVideoMimeTypes(MimeTypes.VIDEO_H264).setAllowVideoMixedMimeTypeAdaptiveness(false))
        }
        selector = tracks
        val renderers = DefaultRenderersFactory(context)
            // Uses DefaultMediaCodecAdapterFactory's async queueing on supported API levels.
            .forceEnableMediaCodecAsynchronousQueueing()
            .setEnableDecoderFallback(true)
        return ExoPlayer.Builder(context, renderers).setTrackSelector(tracks)
            .setLoadControl(LowRamLoadControl.create()).setMediaSourceFactory(sourceFactory)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build().also { player ->
                instance = player
                player.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        fallbackJob?.cancel()
                        if (playbackState == Player.STATE_BUFFERING && player.playWhenReady && !fallbackUsed) {
                            fallbackJob = scope.launch { delay(8_000); fallback("Slow connection: using a smaller stream.") }
                        }
                        if (playbackState == Player.STATE_ENDED) {
                            // Stop releases renderers/allocator; keep metadata for an explicit replay.
                            player.stop()
                        }
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        val networkError = error.errorCode in 2000..2999
                        if (!networkError || !fallback("Playback switched to a simpler stream.")) {
                            mutable.value = mutable.value.copy(error = "Playback unavailable (${error.errorCodeName}). Retry to refresh stream URLs.")
                            player.stop()
                        }
                    }
                })
            }
    }
    fun play(video: Video) {
        resolveJob?.cancel(); fallbackJob?.cancel()
        instance?.stop(); instance?.clearMediaItems()
        streams = null; selected = null; fallbackUsed = false
        mutable.value = PlaybackState(video = video, resolving = true)
        resolveJob = scope.launch {
            try {
                val result = resolver.resolve(video.id)
                if (instance == null) throw CancellationException("Player released during resolution")
                streams = result
                selected = result.videos.filter { it.height <= 720 && (!it.videoOnly || result.audio != null) }.lastOrNull()
                    ?: result.videos.firstOrNull { !it.videoOnly || result.audio != null }
                mutable.value = mutable.value.copy(resolving = false, qualities = result.videos.filter {
                    !it.videoOnly || result.audio != null
                }.toPersistentList())
                if (videoVisible || allowBackground) install(0, true, audioOnly = !videoVisible)
                else instance?.stop()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutable.value = mutable.value.copy(resolving = false,
                    error = "Could not resolve this video. It may be restricted, rate-limited, or require an extractor update.")
            }
        }
    }
    fun quality(id: String?) {
        val set = streams ?: return
        val option = if (id == null) set.videos.lastOrNull { it.height <= 720 && (!it.videoOnly || set.audio != null) }
            else set.videos.firstOrNull { it.id == id }
        if (option == null) return
        selected = option
        mutable.value = mutable.value.copy(selected = id, notice = null, error = null)
        fallbackUsed = false
        install(instance?.currentPosition ?: 0, instance?.playWhenReady == true, !videoVisible)
    }
    private fun item(url: String): MediaItem {
        val video = mutable.value.video
        return MediaItem.Builder().setUri(Uri.parse(url)).setMediaId(video?.id.orEmpty())
            .setMediaMetadata(MediaMetadata.Builder().setTitle(video?.title).setArtist(video?.channel)
                .setArtworkUri(video?.thumbnail?.takeIf { it.isNotBlank() }?.let(Uri::parse)).build()).build()
    }
    private fun source(url: String): MediaSource = sourceFactory.createMediaSource(item(url))
    private fun install(position: Long, play: Boolean, audioOnly: Boolean) {
        val set = streams ?: return
        val player = instance ?: return
        val video = selected
        val media = when {
            audioOnly && set.audio != null -> source(set.audio)
            video == null -> {
                set.manifest?.let { source(it) }
                    ?: run { mutable.value = mutable.value.copy(error = "No playable audio/video combination."); return }
            }
            video.videoOnly && set.audio != null -> MergingMediaSource(true, source(video.url), source(set.audio))
            !video.videoOnly -> source(video.url)
            else -> { mutable.value = mutable.value.copy(error = "No playable audio/video combination."); return }
        }
        selector?.let { it.setParameters(it.buildUponParameters().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, audioOnly)) }
        // Replacing with a genuine audio-only source also stops downloading video, unlike hiding a view.
        player.setMediaSource(media, position.coerceAtLeast(0))
        player.prepare(); player.playWhenReady = play
        mutable.value = mutable.value.copy(audioOnly = audioOnly)
    }
    fun visibility(visible: Boolean, backgroundAudio: Boolean) {
        videoVisible = visible; allowBackground = backgroundAudio
        val player = instance ?: return
        if (streams == null) return
        if (!visible && (!backgroundAudio || !player.playWhenReady)) {
            player.stop() // no paused video decoder retained in background
            return
        }
        if (mutable.value.audioOnly != !visible || player.playbackState == Player.STATE_IDLE) {
            install(player.currentPosition, player.playWhenReady, !visible)
        }
    }
    private fun fallback(reason: String): Boolean {
        if (fallbackUsed || mutable.value.audioOnly) return false
        val muxed = streams?.videos?.filter { !it.videoOnly && it.height <= (selected?.height ?: 720) }
            ?.minByOrNull { it.height } ?: return false
        if (selected?.id == muxed.id) return false
        fallbackUsed = true
        selected = muxed
        mutable.value = mutable.value.copy(selected = muxed.id, notice = reason, error = null)
        install(instance?.currentPosition ?: 0, instance?.playWhenReady == true, false)
        return true
    }
    fun stop() {
        resolveJob?.cancel(); fallbackJob?.cancel()
        instance?.stop(); instance?.clearMediaItems()
        streams = null; selected = null
        mutable.value = PlaybackState()
    }
    fun release() {
        stop(); scope.cancel(); instance?.release(); instance = null; selector = null
    }
}
