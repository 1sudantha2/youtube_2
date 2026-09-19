package com.youtubelite.app.player

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.youtubelite.app.AppGraph
import com.youtubelite.app.model.QualityOption
import com.youtubelite.app.util.getS
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * Foreground MediaSessionService owning the single ExoPlayer instance.
 *
 * - Builds MergingMediaSource (video-only + audio) for adaptive DASH playback,
 *   or a single progressive source as network-degradation fallback.
 * - AUTO mode demotes one quality step after repeated rebuffers, ending in the
 *   standard muxed stream — resilient on flaky budget-device connections.
 * - Audio-only mode (screen off / background) calls clearVideoSurface(), which
 *   disables the video renderer and releases the media codec immediately.
 */
class PlaybackService : MediaSessionService() {

    private inner class ActivePlay(
        val videoId: String,
        val title: String,
        val channel: String,
        val thumb: String,
        val qualities: List<QualityOption>,
        val muxed: List<QualityOption>,
        val audioUrl: String?,
        var auto: Boolean,
        var qualityIndex: Int, // -1 => muxed fallback
    )

    private var session: MediaSession? = null
    private lateinit var dataSourceFactory: OkHttpDataSource.Factory
    private val extractors = DefaultExtractorsFactory()

    @Volatile
    private var current: ActivePlay? = null

    private var wasReady = false
    private var rebufferCount = 0

    override fun onCreate() {
        super.onCreate()
        dataSourceFactory = OkHttpDataSource.Factory(AppGraph.mediaClient)
        val player = ExoPlayer.Builder(this, PlayerEngine.renderersFactory(this))
            .setLoadControl(PlayerEngine.loadControl())
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.addListener(playerListener)
        session = MediaSession.Builder(this, player)
            .setCallback(sessionCallback)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ //

    private val sessionCallback = object : MediaSession.Callback {

        override fun onCustomCommand(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            val extras = customCommand.customExtras
            when (customCommand.customAction) {
                PlayerContract.CMD_PLAY -> {
                    val qualities = decodeQualities(extras.getString(PlayerContract.EX_QUALITIES))
                    val muxed = decodeQualities(extras.getString(PlayerContract.EX_MUXED))
                    val id = extras.getString(PlayerContract.EX_ID)
                    if (id == null) return done()
                    val active = ActivePlay(
                        videoId = id,
                        title = extras.getString(PlayerContract.EX_TITLE).orEmpty(),
                        channel = extras.getString(PlayerContract.EX_CHANNEL).orEmpty(),
                        thumb = extras.getString(PlayerContract.EX_THUMB).orEmpty(),
                        qualities = qualities,
                        muxed = muxed,
                        audioUrl = extras.getString(PlayerContract.EX_AUDIO),
                        auto = extras.getBoolean(PlayerContract.EX_AUTO, true),
                        qualityIndex = -1,
                    )
                    active.qualityIndex = if (active.auto) {
                        initialAutoIndex(active)
                    } else {
                        active.qualities.lastIndex
                    }
                    current = active
                    rebufferCount = 0
                    wasReady = false
                    start(active, 0L)
                }

                PlayerContract.CMD_QUALITY -> {
                    val active = current ?: return done()
                    val raw = extras.getString(PlayerContract.EX_LABEL).orEmpty()
                    if (raw.endsWith("|muxed")) {
                        // Pin the standard progressive (video+audio) fallback stream.
                        active.qualityIndex = -1
                        active.auto = false
                        start(active, positionMs())
                    } else {
                        val index = active.qualities.indexOfFirst { it.label == raw }
                        if (index >= 0) {
                            active.qualityIndex = index
                            active.auto = false
                            start(active, positionMs())
                        }
                    }
                }

                PlayerContract.CMD_AUTO -> {
                    val active = current ?: return done()
                    active.auto = true
                    active.qualityIndex = initialAutoIndex(active)
                    start(active, positionMs())
                }

                PlayerContract.CMD_AUDIO_ONLY -> {
                    val on = extras.getBoolean(PlayerContract.EX_ON, false)
                    if (on) {
                        // Releases the video codec instantly; audio keeps playing.
                        session?.player?.clearVideoSurface()
                    }
                    // on == false: PlayerView re-attaches the surface from the UI side.
                }

                PlayerContract.CMD_STOP -> {
                    current = null
                    session?.player?.let {
                        it.stop()
                        it.clearMediaItems()
                    }
                }
            }
            return done()
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
            Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(ImmutableList.of(), 0, C.TIME_UNSET)
            )
    }

    private val playerListener = object : Player.Listener {

        override fun onPlaybackStateChanged(playbackState: Int) {
            val active = current ?: return
            val player = session?.player ?: return
            when (playbackState) {
                Player.STATE_READY -> {
                    wasReady = true
                    rebufferCount = 0
                }

                Player.STATE_BUFFERING -> {
                    if (wasReady && player.playWhenReady) {
                        rebufferCount++
                        wasReady = false
                        if (active.auto && rebufferCount >= 2) demote(active)
                    }
                }

                else -> Unit
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val active = current ?: return
            when {
                // DASH merge failed (expired URL, codec, CDN) -> standard muxed format.
                active.qualityIndex >= 0 && active.muxed.isNotEmpty() -> {
                    active.qualityIndex = -1
                    start(active, positionMs())
                }

                active.qualityIndex > 0 -> {
                    active.qualityIndex--
                    start(active, positionMs())
                }

                else -> Unit
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // Wake the session so the media notification state stays truthful.
            if (isPlaying) publishExtras(current ?: return)
        }
    }

    // ------------------------------------------------------------------ //

    private fun demote(active: ActivePlay) {
        rebufferCount = 0
        when {
            active.qualityIndex > 0 -> {
                active.qualityIndex--
                start(active, positionMs())
            }

            // Bottom of the adaptive ladder -> fall back to the muxed stream.
            active.qualityIndex == 0 && active.muxed.isNotEmpty() -> {
                active.qualityIndex = -1
                start(active, positionMs())
            }

            else -> Unit
        }
    }

    private fun initialAutoIndex(active: ActivePlay): Int {
        val cap = minOf(resources.displayMetrics.heightPixels, 1080)
        var idx = 0
        for (i in active.qualities.indices) {
            if (active.qualities[i].height <= cap) idx = i
        }
        return idx
    }

    private fun positionMs(): Long = session?.player?.currentPosition ?: 0L

    private fun start(active: ActivePlay, startMs: Long) {
        // MediaSource-based playback control lives on ExoPlayer, not Player.
        val player = session?.player as? ExoPlayer ?: return
        val source: MediaSource? = when {
            active.qualityIndex >= 0 && active.qualities.getOrNull(active.qualityIndex) != null &&
                active.audioUrl != null -> {
                val q = active.qualities[active.qualityIndex]
                MergingMediaSource(videoSource(active, q.url), audioSource(active.audioUrl))
            }

            active.muxed.isNotEmpty() ->
                videoSource(active, active.muxed.last().url)

            active.audioUrl != null ->
                audioSource(active.audioUrl) // audio-only fallback (rare)

            else -> null
        } ?: return

        if (startMs > 0) player.setMediaSource(source, startMs)
        else player.setMediaSource(source)
        player.prepare()
        player.playWhenReady = true
        publishExtras(active)
    }

    private fun videoSource(active: ActivePlay, url: String): ProgressiveMediaSource {
        val item = MediaItem.Builder()
            .setMediaId(active.videoId)
            .setUri(Uri.parse(url))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(active.title)
                    .setArtist(active.channel)
                    .setArtworkUri(Uri.parse(active.thumb))
                    .build()
            )
            .build()
        return ProgressiveMediaSource.Factory(dataSourceFactory, extractors)
            .createMediaSource(item)
    }

    private fun audioSource(url: String): ProgressiveMediaSource =
        ProgressiveMediaSource.Factory(dataSourceFactory, extractors)
            .createMediaSource(MediaItem.fromUri(Uri.parse(url)))

    private fun publishExtras(active: ActivePlay) {
        val label = when {
            active.qualityIndex >= 0 ->
                active.qualities.getOrNull(active.qualityIndex)?.label ?: "Auto"

            active.muxed.isNotEmpty() -> "${active.muxed.last().label} (fallback)"
            else -> "Audio"
        }
        session?.setSessionExtras(
            Bundle().apply {
                putString(PlayerContract.EXTRA_ACTIVE_LABEL, label)
                putBoolean(PlayerContract.EXTRA_AUTO, active.auto)
            }
        )
    }

    private fun decodeQualities(payload: String?): List<QualityOption> {
        if (payload.isNullOrEmpty()) return emptyList()
        return try {
            val arr = AppGraph.json.parseToJsonElement(payload) as? JsonArray ?: return emptyList()
            arr.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val label = o.getS("label") ?: return@mapNotNull null
                val url = o.getS("url") ?: return@mapNotNull null
                QualityOption(
                    label = label,
                    height = o.getS("height")?.toIntOrNull() ?: 0,
                    url = url,
                    fps = o.getS("fps")?.toIntOrNull() ?: 30,
                )
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun done(): ListenableFuture<SessionResult> =
        Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
}
