package com.ultra.youtube.app.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.ultra.youtube.app.MainActivity
import com.ultra.youtube.app.data.auth.CookieStore
import com.ultra.youtube.app.player.LowRamLoadControl
import com.ultra.youtube.app.player.LowRamPlayer

/**
 * Background audio.
 *
 * A [MediaSessionService] is the only component allowed to keep playing after the UI goes
 * away; the system promotes it to the foreground with a media notification, and Android
 * 14+ requires the `mediaPlayback` foreground-service type declared in the manifest.
 *
 * The player here is built with the same [LowRamLoadControl] as the in-app player, so
 * background audio costs at most the same 20 MB of buffer — usually far less, because the
 * video track is disabled and only the audio stream is buffered.
 */
@UnstableApi
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()

        val cookieStore = CookieStore(this)
        val httpFactory = LowRamPlayer.httpDataSourceFactory(cookieStore.session.value?.header)
        val dataSourceFactory =
            androidx.media3.datasource.DefaultDataSource.Factory(this, httpFactory)

        val player = ExoPlayer.Builder(
            this,
            androidx.media3.exoplayer.DefaultRenderersFactory(this)
                .setExtensionRendererMode(
                    androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF,
                ),
        )
            .setLoadControl(LowRamLoadControl.build())
            .setMediaSourceFactory(
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dataSourceFactory),
            )
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus= */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        // Audio only: never let the service allocate a video decoder.
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
            .build()

        val sessionIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionIntent)
            .setCallback(MediaSessionCallback())
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }

    /** Default callback: this app drives playback from the UI, not from the session. */
    private class MediaSessionCallback : MediaSession.Callback

    companion object {
        /** Intent used by [androidx.media3.session.MediaController] to bind to this service. */
        fun sessionIntent(context: Context): Intent = Intent(context, PlaybackService::class.java)
    }
}
