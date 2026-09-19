package io.github.sudantha.youtubelite.player

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import io.github.sudantha.youtubelite.MainActivity
import io.github.sudantha.youtubelite.YouTubeApp

class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    override fun onCreate() {
        super.onCreate()
        val engine = (application as YouTubeApp).engine
        val activity = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaSession.Builder(this, engine.create()).setSessionActivity(activity).build()
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session
    override fun onTaskRemoved(rootIntent: Intent?) {
        // Explicitly swiping the task away stops playback and tears down resources.
        (application as YouTubeApp).engine.stop()
        stopSelf()
    }
    override fun onDestroy() {
        session?.release(); session = null
        (application as YouTubeApp).engine.release()
        super.onDestroy()
    }
}
