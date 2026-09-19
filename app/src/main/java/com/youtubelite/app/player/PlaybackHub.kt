package com.youtubelite.app.player

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.youtubelite.app.model.QualityOption
import com.youtubelite.app.model.WatchMeta
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.lang.ref.WeakReference
import kotlin.coroutines.resume

/**
 * UI-side bridge to PlaybackService through a Media3 MediaController.
 * All state the UI needs is exposed as StateFlows; fast-moving values
 * (position) are NEVER exposed here — PlayerView renders those internally,
 * keeping Compose recompositions at zero during playback.
 */
object PlaybackHub {

    private val _now = MutableStateFlow<WatchMeta?>(null)
    val now: StateFlow<WatchMeta?> = _now

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    private val _activeLabel = MutableStateFlow<String?>(null)
    val activeLabel: StateFlow<String?> = _activeLabel

    private val _isAuto = MutableStateFlow(true)
    val isAuto: StateFlow<Boolean> = _isAuto

    private val _audioOnly = MutableStateFlow(false)
    val audioOnly: StateFlow<Boolean> = _audioOnly

    @Volatile
    private var controller: MediaController? = null

    private var playerViewRef: WeakReference<androidx.media3.ui.PlayerView>? = null
    private val connectMutex = Mutex()

    fun init(appContext: Context) {
        this.appContext = appContext.applicationContext
    }

    private lateinit var appContext: Context

    suspend fun ensureConnected(): Boolean {
        controller?.let { return true }
        return connectMutex.withLock {
            controller?.let { return true }
            suspendCancellableCoroutine { cont ->
                try {
                    val token = SessionToken(
                        appContext,
                        ComponentName(appContext, PlaybackService::class.java),
                    )
                    val future = MediaController.Builder(appContext, token)
                        .setListener(controllerListener)
                        .buildAsync()
                    future.addListener({
                        try {
                            val c = future.get()
                            c.addListener(playerObserver)
                            controller = c
                            // Attach to a PlayerView created before the connection existed.
                            playerViewRef?.get()?.player = c
                            cont.resume(true)
                        } catch (t: Throwable) {
                            cont.resume(false)
                        }
                    }, MoreExecutors.directExecutor())
                } catch (t: Throwable) {
                    cont.resume(false)
                }
            }
        }
    }

    private val controllerListener = object : MediaController.Listener {
        override fun onExtrasChanged(controller: MediaController, extras: Bundle) {
            _activeLabel.value = extras.getString(PlayerContract.EXTRA_ACTIVE_LABEL)
            _isAuto.value = extras.getBoolean(PlayerContract.EXTRA_AUTO, true)
        }
    }

    private val playerObserver = object : androidx.media3.common.Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
        }
    }

    // ------------------------------------------------------------------ //

    fun play(meta: WatchMeta, streams: com.youtubelite.app.model.StreamSet, auto: Boolean) {
        val c = controller ?: return
        val extras = Bundle().apply {
            putString(PlayerContract.EX_ID, meta.videoId)
            putString(PlayerContract.EX_TITLE, meta.title)
            putString(PlayerContract.EX_CHANNEL, meta.channel)
            putString(PlayerContract.EX_THUMB, meta.thumb)
            putString(PlayerContract.EX_AUDIO, streams.audioUrl)
            putString(PlayerContract.EX_QUALITIES, encodeQualities(streams.qualities))
            putString(PlayerContract.EX_MUXED, encodeQualities(streams.muxed))
            putBoolean(PlayerContract.EX_AUTO, auto)
        }
        _now.value = meta
        c.sendCustomCommand(SessionCommand(PlayerContract.CMD_PLAY, extras), Bundle.EMPTY)
    }

    fun setQuality(label: String) {
        val c = controller ?: return
        _isAuto.value = false
        c.sendCustomCommand(
            SessionCommand(
                PlayerContract.CMD_QUALITY,
                Bundle().apply { putString(PlayerContract.EX_LABEL, label) },
            ),
            Bundle.EMPTY,
        )
    }

    fun setAuto() {
        val c = controller ?: return
        _isAuto.value = true
        c.sendCustomCommand(SessionCommand(PlayerContract.CMD_AUTO, Bundle.EMPTY), Bundle.EMPTY)
    }

    fun setAudioOnly(on: Boolean) {
        _audioOnly.value = on
        val c = controller ?: return
        if (on) c.clearVideoSurface()
        c.sendCustomCommand(
            SessionCommand(
                PlayerContract.CMD_AUDIO_ONLY,
                Bundle().apply { putBoolean(PlayerContract.EX_ON, on) },
            ),
            Bundle.EMPTY,
        )
        if (!on) reattachSurface()
    }

    fun playPause() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs)
    }

    fun currentPosition(): Long = controller?.currentPosition ?: 0L

    fun stop() {
        _now.value = null
        _audioOnly.value = false
        _activeLabel.value = null
        controller?.sendCustomCommand(
            SessionCommand(PlayerContract.CMD_STOP, Bundle.EMPTY),
            Bundle.EMPTY,
        )
    }

    // ------------------------------------------------------------------ //
    // Surface lifecycle (decoder release when backgrounded)

    fun bindPlayerView(view: androidx.media3.ui.PlayerView?) {
        playerViewRef = WeakReference(view)
        view?.player = controller
    }

    /** Called from Activity ON_STOP: releases the video decoder, keeps audio. */
    fun onBackgrounded() {
        if (!_audioOnly.value) controller?.clearVideoSurface()
    }

    /** Called from Activity ON_RESUME: re-attaches the SurfaceView. */
    fun onForegrounded() {
        if (!_audioOnly.value) reattachSurface()
    }

    private fun reattachSurface() {
        val view = playerViewRef?.get() ?: return
        view.player = null
        view.player = controller
    }

    private fun encodeQualities(list: List<QualityOption>): String = buildJsonArray {
        for (q in list) {
            add(
                buildJsonObject {
                    put("label", q.label)
                    put("height", q.height)
                    put("url", q.url)
                    put("fps", q.fps)
                }
            )
        }
    }.toString()
}
