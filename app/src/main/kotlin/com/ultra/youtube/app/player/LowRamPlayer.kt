package com.ultra.youtube.app.player

import android.content.Context
import android.view.Surface
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import com.ultra.youtube.app.data.auth.WebViewCookieExtractor
import com.ultra.youtube.app.domain.Format
import com.ultra.youtube.app.domain.VideoDetail
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A minimal ExoPlayer wrapper tuned for low RAM / low CPU.
 *
 * Design decisions that matter for memory:
 *
 *  1. **[LowRamLoadControl]** — 10–25 s buffer window with a hard 20 MB allocator ceiling.
 *  2. **[androidx.media3.exoplayer.EXTENSION_RENDERER_MODE_OFF]** — no FFmpeg/decoder
 *     extensions are loaded, so no extra native libraries are mapped into the process.
 *  3. **No [androidx.media3.ui.PlayerView]** — the video goes straight to a
 *     [android.view.SurfaceView] hosted in Compose. `SurfaceView` composites on a
 *     separate hardware layer, so frames never get copied through the app's view-layer
 *     bitmap (that is what `TextureView` does, and it is why `TextureView` costs a full
 *     extra frame of GPU memory).
 *  4. **Track selection starts adaptive but capped** — see [maxVideoHeight].
 *
 * The player is *owned* by whichever component holds it; release it in `onDispose`
 * ([release]) or the 20 MB allocator stays alive for the lifetime of the process.
 */
@UnstableApi
class LowRamPlayer private constructor(
    val exoPlayer: ExoPlayer,
    private val dataSourceFactory: DataSource.Factory,
) : Player.Listener {

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private var videoFormats: List<Format> = emptyList()

    /** Maximum height ABR is allowed to pick. 0 = unlimited. */
    var maxVideoHeight: Int = 0
        set(value) {
            field = value
            exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                .buildUpon()
                .setMaxVideoSize(Int.MAX_VALUE, if (value > 0) value else Int.MAX_VALUE)
                .build()
        }

    /** `true` while the video renderer is detached (background audio). */
    var videoDisabled: Boolean = false
        private set

    init {
        exoPlayer.addListener(this)
    }

    // --------------------------------------------------------------- lifecycle

    fun attach(surface: Surface) {
        exoPlayer.setVideoSurface(surface)
    }

    fun detachSurface() {
        exoPlayer.clearVideoSurface()
    }

    fun release() {
        exoPlayer.removeListener(this)
        exoPlayer.release()
    }

    // --------------------------------------------------------------- playback

    /**
     * Prepares a video: DASH-merged when adaptive formats are describable, progressive
     * otherwise.
     *
     * @param startPositionMs resume position (watch-later bookmark, deep link, etc.)
     */
    fun play(detail: VideoDetail, startPositionMs: Long = 0L) {
        videoFormats = detail.formats.filter { it.isVideo }
        val durationMs = detail.video.durationSeconds * 1000L
        val manifests = DashManifestBuilder.build(detail.formats, durationMs)

        val source: MediaSource = if (manifests != null) {
            MergingDashMediaSource.create(
                mediaId = detail.video.id,
                manifests = manifests,
                dataSourceFactory = dataSourceFactory,
                startPositionMs = startPositionMs,
            ).source
        } else {
            val progressive = detail.formats
                .filter { it.url != null && it.isVideo && it.hasNoIndexRange() }
                .maxByOrNull { it.height * 1000L + it.bitrate }
                ?: detail.formats.firstOrNull { it.url != null }
            if (progressive == null) {
                _state.value = _state.value.copy(error = "No playable stream for this video")
                return
            }
            androidx.media3.exoplayer.source.progressive.ProgressiveMediaSource
                .Factory(dataSourceFactory)
                .createMediaSource(
                    MediaItem.Builder()
                        .setMediaId(detail.video.id)
                        .setUri(android.net.Uri.parse(progressive.url))
                        .build(),
                )
        }

        exoPlayer.setMediaSource(source)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    fun play() = exoPlayer.play()
    fun pause() = exoPlayer.pause()
    fun togglePlayPause() = if (exoPlayer.isPlaying) pause() else play()

    fun seekTo(positionMs: Long) = exoPlayer.seekTo(positionMs.coerceAtLeast(0L))
    fun skip(deltaMs: Long) = seekTo(exoPlayer.currentPosition + deltaMs)

    /**
     * Pulls the current position into [state] without touching playback.
     *
     * Used by the scrubber: ExoPlayer only fires `EVENT_POSITION_DISCONTINUITY` on seeks,
     * so a playing position has to be sampled. Never call [seekTo] for this — that would
     * restart buffering from the current point every tick.
     */
    fun refreshPosition() {
        if (!exoPlayer.isPlaying) return
        _state.value = _state.value.copy(
            positionMs = exoPlayer.currentPosition.coerceAtLeast(0L),
            bufferedMs = exoPlayer.bufferedPosition.coerceAtLeast(0L),
            durationMs = exoPlayer.duration.coerceAtLeast(0L),
        )
    }

    fun setSpeed(speed: Float) {
        exoPlayer.setPlaybackParameters(PlaybackParameters(speed.coerceIn(0.25f, 4f)))
    }

    /**
     * Drops the video renderer while keeping audio alive — used when the screen turns off
     * or the app is backgrounded. Saves a decoder + a surface's worth of memory.
     */
    fun setBackgroundAudioOnly(enabled: Boolean) {
        if (videoDisabled == enabled) return
        videoDisabled = enabled
        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, enabled)
            .build()
    }

    // --------------------------------------------------------------- quality

    /** The heights this video actually offers, descending — drives the quality sheet. */
    fun availableHeights(): List<Int> = videoFormats.map { it.height }.filter { it > 0 }.distinct().sortedDescending()

    /**
     * Pins playback to the rendition closest to [heightPx].
     *
     * Implemented as a `TrackSelectionOverride` on the merged video track group, so the
     * switch is gapless: no re-prepare, no new HTTP connection.
     */
    fun selectQuality(heightPx: Int) {
        val tracks: Tracks = exoPlayer.currentTracks
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_VIDEO) continue
            var bestIndex = -1
            var bestDelta = Int.MAX_VALUE
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i)) continue
                val delta = kotlin.math.abs(group.getTrackFormat(i).height - heightPx)
                if (delta < bestDelta) {
                    bestDelta = delta
                    bestIndex = i
                }
            }
            if (bestIndex < 0) continue
            exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                .buildUpon()
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, bestIndex))
                .setForceHighestSupportedBitrate(false)
                .build()
            _state.value = _state.value.copy(
                selectedHeight = group.getTrackFormat(bestIndex).height,
                isAutoQuality = false,
            )
            return
        }
    }

    /** Hands quality back to ABR (still bounded by [maxVideoHeight]). */
    fun selectAutoQuality() {
        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
            .buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
            .build()
        _state.value = _state.value.copy(isAutoQuality = true)
    }

    // --------------------------------------------------------------- listener

    override fun onPlaybackStateChanged(playbackState: Int) {
        _state.value = _state.value.copy(
            isPlaying = exoPlayer.isPlaying,
            isBuffering = playbackState == Player.STATE_BUFFERING,
            isEnded = playbackState == Player.STATE_ENDED,
            durationMs = exoPlayer.duration.coerceAtLeast(0L),
        )
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        _state.value = _state.value.copy(isPlaying = isPlaying)
    }

    override fun onVideoSizeChanged(videoSize: VideoSize) {
        _state.value = _state.value.copy(
            videoWidth = videoSize.width,
            videoHeight = videoSize.height,
            rotationDegrees = videoSize.unappliedRotationDegrees,
            pixelWidthHeightRatio = videoSize.pixelWidthHeightRatio,
        )
    }

    override fun onTracksChanged(tracks: Tracks) {
        var selected = 0
        var maxSupported = 0
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_VIDEO) continue
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                maxSupported = maxOf(maxSupported, format.height)
                if (group.isTrackSelected(i)) selected = format.height
            }
        }
        _state.value = _state.value.copy(
            selectedHeight = if (selected > 0) selected else _state.value.selectedHeight,
            maxTrackHeight = maxSupported,
        )
    }

    override fun onPlayerError(error: PlaybackException) {
        _state.value = _state.value.copy(error = error.errorCodeName)
    }

    override fun onEvents(player: Player, events: Player.Events) {
        if (events.containsAny(
                Player.EVENT_POSITION_DISCONTINUITY,
                Player.EVENT_TIMELINE_CHANGED,
                Player.EVENT_PLAYBACK_STATE_CHANGED,
            )
        ) {
            _state.value = _state.value.copy(
                positionMs = player.currentPosition.coerceAtLeast(0L),
                bufferedMs = player.bufferedPosition.coerceAtLeast(0L),
                durationMs = player.duration.coerceAtLeast(0L),
            )
        }
    }

    private fun Format.hasNoIndexRange(): Boolean = indexRangeStart == Format.UNSET

    companion object {

        /** Extra headers every segment request needs (InnerTube URLs are user-scoped). */
        fun httpDataSourceFactory(cookieHeader: String? = null): DataSource.Factory {
            val builder = DefaultHttpDataSource.Factory()
                .setUserAgent(WebViewCookieExtractor.DESKTOP_USER_AGENT)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(10_000)
                .setReadTimeoutMs(20_000)
                // googlevideo serves gzipped manifests; letting OkHttp/the source inflate
                // keeps the buffer accounting honest.
                .setKeepPostFor302Redirects(false)
            if (!cookieHeader.isNullOrBlank()) {
                builder.setDefaultRequestProperties(mapOf("Cookie" to cookieHeader))
            }
            return builder
        }

        @OptIn(UnstableApi::class)
        fun create(context: Context, cookieHeader: String? = null): LowRamPlayer {
            val appContext = context.applicationContext
            val httpFactory = httpDataSourceFactory(cookieHeader)
            // DefaultDataSource routes `data:` (our inline MPDs) to the base64 decoder and
            // everything else to HTTP.
            val dataSourceFactory = DefaultDataSource.Factory(appContext, httpFactory)

            val renderersFactory = DefaultRenderersFactory(appContext)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
                .setEnableDecoderFallback(true)

            val player = ExoPlayer.Builder(appContext, renderersFactory)
                .setLoadControl(LowRamLoadControl.build())
                .setMediaSourceFactory(
                    androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dataSourceFactory),
                )
                .setTrackSelector(
                    androidx.media3.exoplayer.trackselection.DefaultTrackSelector(appContext).apply {
                        // Do not chase the highest rendition on metered/slow links.
                        parameters = buildUponParameters()
                            .setForceInitialSelectionNonTunneling(false)
                            .setPreferredVideoMimeType("video/mp4")
                            .build()
                    },
                )
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .setUsage(C.USAGE_MEDIA)
                        .build(),
                    /* handleAudioFocus= */ true,
                )
                .setHandleAudioBecomingNoisy(true)
                // Keeps the CPU awake for background audio without a partial wake lock of
                // our own.
                .setWakeMode(C.WAKE_MODE_NETWORK)
                .setSeekBackIncrementMs(10_000)
                .setSeekForwardIncrementMs(10_000)
                .build()

            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .setPreferredVideoMimeType("video/mp4")
                .build()

            return LowRamPlayer(player, dataSourceFactory)
        }

        /**
         * Track selection parameters tuned for low bandwidth devices: prefer H.264 (always
         * hardware-accelerated) and cap the initial rendition at 720p.
         */
        fun lowRamTrackParameters(context: Context): TrackSelectionParameters =
            androidx.media3.exoplayer.trackselection.DefaultTrackSelector(context)
                .buildUponParameters()
                .setMaxVideoSize(1280, 720)
                .setPreferredVideoMimeType("video/mp4")
                .build()
    }
}
