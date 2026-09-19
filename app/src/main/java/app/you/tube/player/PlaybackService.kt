package app.you.tube.player

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import app.you.tube.MainActivity
import app.you.tube.R

/**
 * Foreground [MediaSessionService].
 *
 * The ExoPlayer instance + MediaSession live here; the UI drives playback
 * through a MediaController bound to this session (same process). While the
 * app is in the foreground the SurfaceView attaches through the controller;
 * when the screen turns off the UI disables the video track (decoder released,
 * audio-only playback) and this service keeps the audio alive with a
 * low-overhead media notification. The service demotes itself from foreground
 * (and is torn down by the system) once playback stops — zero idle cost.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        // Must be set before super.onCreate() returns.
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelName(R.string.notification_channel_name)
                .build()
                .apply { setSmallIcon(R.drawable.ic_stat_play) }
        )
        super.onCreate()

        val player = PlayerFactory.create(this)
        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivity)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        mediaSession?.let { session ->
            runCatching { session.player.release() }
            runCatching { session.release() }
        }
        mediaSession = null
        super.onDestroy()
    }
}
