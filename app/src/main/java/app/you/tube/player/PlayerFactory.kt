package app.you.tube.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.AudioAttributes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultTrackSelector
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import app.you.tube.core.extract.StreamRegistry
import app.you.tube.core.network.InnerTubeClient
import app.you.tube.core.network.OkHttpProvider

/**
 * Builds the process ExoPlayer instance.
 *
 * Performance contract:
 *  - `forceEnableMediaCodecAsynchronousQueueing()`: MediaCodec runs in
 *    asynchronous buffer-queueing mode (AsynchronousMediaCodecAdapter) on
 *    every API 23+ device — decoder callbacks arrive off the playback thread,
 *    eliminating dequeue-polling stalls and frame-drop bursts.
 *  - `setEnableDecoderFallback(true)`: automatic codec switch when a hardware
 *    decoder misbehaves — critical on fragmented budget SoCs.
 *  - `LowRamLoadControl`: 20 MB / 10–25 s buffer envelope.
 *  - Network feeding rides the shared OkHttp client (HTTP/2 + DNS cache).
 *  - Audio focus + noisy-output handling (headphone unplug pauses).
 *  - WAKE_MODE_NETWORK: keep CPU/radio awake only while buffering, never
 *    while paused (battery-friendly).
 */
object PlayerFactory {

    @OptIn(UnstableApi::class)
    fun create(context: Context): ExoPlayer {
        val appContext = context.applicationContext

        val httpDataSourceFactory = OkHttpDataSource.Factory(OkHttpProvider.get(appContext))
            .setUserAgent(InnerTubeClient.USER_AGENT)

        val dataSourceFactory = DefaultDataSource.Factory(appContext, httpDataSourceFactory)

        val renderersFactory = DefaultRenderersFactory(appContext)
            .setEnableDecoderFallback(true)
            .forceEnableMediaCodecAsynchronousQueueing()

        return ExoPlayer.Builder(appContext)
            .setRenderersFactory(renderersFactory)
            .setTrackSelector(DefaultTrackSelector(appContext))
            .setLoadControl(LowRamLoadControl())
            .setMediaSourceFactory(PlayerMediaSourceFactory(dataSourceFactory))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
    }
}

/**
 * MediaSource factory used by the session-side player.
 *
 * Recognizes the internal `ytmerge://<videoId>?v=<videoItag>&a=<audioItag>`
 * scheme: the controller side sends a tiny custom URI, and here (same process)
 * it is expanded into a [MergingMediaSource] that plays a DASH video-only
 * stream (144p–1080p) in lock-step with an audio-only Opus/AAC stream —
 * durations clipped to the shorter of the two and timestamps aligned.
 * Everything else (muxed progressive, HLS for live) falls through to the
 * stock [DefaultMediaSourceFactory].
 */
@OptIn(UnstableApi::class)
class PlayerMediaSourceFactory(
    private val dataSourceFactory: DataSource.Factory
) : MediaSource.Factory {

    private val delegate = DefaultMediaSourceFactory(dataSourceFactory)
    private val progressiveFactory = ProgressiveMediaSource.Factory(dataSourceFactory)

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val uri = mediaItem.localConfiguration?.uri
        if (uri?.scheme == SCHEME) {
            val videoId = uri.host.orEmpty()
            val videoItag = uri.getQueryParameter(PARAM_VIDEO)?.toIntOrNull()
            val audioItag = uri.getQueryParameter(PARAM_AUDIO)?.toIntOrNull()
            val bundle = StreamRegistry.get(videoId)

            if (bundle != null && videoItag != null) {
                val video = bundle.qualityByItag(videoItag)
                if (video != null && video.url.isNotBlank()) {
                    val audioUrl = bundle.bestAudioUrl?.takeIf { audioItag != null && audioItag == bundle.bestAudioItag }
                    if (!video.isMuxed && !audioUrl.isNullOrBlank()) {
                        // Adaptive path: merge video-only + audio-only sources.
                        val videoSource = progressiveFactory.createMediaSource(MediaItem.fromUri(video.url))
                        val audioSource = progressiveFactory.createMediaSource(MediaItem.fromUri(audioUrl))
                        return MergingMediaSource(
                            /* adjustPeriodTimeOffsets = */ true,
                            /* clipDurations = */ true,
                            videoSource,
                            audioSource
                        )
                    }
                    // No usable audio pairing — degrade to the video-only URL.
                    return progressiveFactory.createMediaSource(MediaItem.fromUri(video.url))
                }
                bundle.fallbackMuxed?.let {
                    return progressiveFactory.createMediaSource(MediaItem.fromUri(it.url))
                }
            }
        }
        return delegate.createMediaSource(mediaItem)
    }

    override fun getSupportedTypes(): IntArray = delegate.supportedTypes

    override fun setDrmSessionManagerProvider(drmSessionManagerProvider: DrmSessionManagerProvider): MediaSource.Factory =
        delegate.setDrmSessionManagerProvider(drmSessionManagerProvider)

    override fun setLoadErrorHandlingPolicy(loadErrorHandlingPolicy: LoadErrorHandlingPolicy): MediaSource.Factory =
        delegate.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)

    companion object {
        const val SCHEME = "ytmerge"
        const val PARAM_VIDEO = "v"
        const val PARAM_AUDIO = "a"

        fun mergedUri(videoId: String, videoItag: Int, audioItag: Int): Uri =
            Uri.parse("$SCHEME://$videoId?$PARAM_VIDEO=$videoItag&$PARAM_AUDIO=$audioItag")
    }
}
