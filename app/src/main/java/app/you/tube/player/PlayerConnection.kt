package app.you.tube.player

import android.content.ComponentName
import android.content.Context
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Connects the UI to [PlaybackService]'s MediaSession via a MediaController.
 * The controller is a full [androidx.media3.common.Player] — commands, state
 * and even the video surface travel through it — so playback survives screen
 * rotation, backgrounding and the player screen being dismissed.
 */
class PlayerConnection(context: Context) {

    private val appContext = context.applicationContext

    fun buildControllerFuture(): ListenableFuture<MediaController> {
        val sessionToken = SessionToken(
            appContext,
            ComponentName(appContext, PlaybackService::class.java)
        )
        return MediaController.Builder(appContext, sessionToken).buildAsync()
    }
}

/** Await a ListenableFuture without pulling in Guava (media3 ships the interface). */
suspend fun <T> ListenableFuture<T>.awaitFuture(): T = suspendCancellableCoroutine { cont ->
    addListener({
        try {
            cont.resume(get())
        } catch (t: Throwable) {
            cont.resumeWithException(t)
        }
    }, Executor { it.run() })
}
