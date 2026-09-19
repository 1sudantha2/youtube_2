package com.ultra.youtube.app.player

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.source.MergingMediaSource

/**
 * DASH video/audio merging for InnerTube streams.
 *
 * InnerTube returns *separate* video and audio URLs per itag. To play them together we
 * synthesise one MPD per media type ([DashManifestBuilder]) and merge the two:
 *
 * ```
 * MergingMediaSource
 *   ├── DashMediaSource(video MPD)   <- 144p…1080p renditions, ABR + manual override
 *   └── DashMediaSource(audio MPD)   <- keeps running when the video renderer is torn down
 * ```
 *
 * The merge itself is [MergingMediaSource] — Media3's own implementation. Wrapping it
 * instead of subclassing keeps us off `@UnstableApi` internals whose signatures change
 * between releases, while still exposing both children so the player can inspect video
 * renditions and tear the video side down for background audio.
 *
 * Both manifests are inlined as RFC 2397 `data:` URIs, so nothing is fetched to "read a
 * manifest": the only network traffic is the media segments themselves. Media3 routes
 * `data:` through its `Base64DecoderDataSource`.
 */
@UnstableApi
class MergingDashMediaSource private constructor() {

    data class Created(
        val source: MergingMediaSource,
        val mediaItem: MediaItem,
        val videoFormats: List<com.ultra.youtube.app.domain.Format>,
        val audioFormats: List<com.ultra.youtube.app.domain.Format>,
    )

    companion object {

        const val AUDIO_MPD_KEY = "com.ultra.youtube.app.AUDIO_MPD"
        const val VIDEO_MPD_KEY = "com.ultra.youtube.app.VIDEO_MPD"

        /** `data:application/dash+xml;base64,<mpd>` — served entirely from memory. */
        fun dataUri(xml: String): Uri = Uri.parse(
            "data:application/dash+xml;base64," +
                android.util.Base64.encodeToString(xml.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP),
        )

        /**
         * @param manifests the video + audio MPD pair
         * @param startPositionMs resume position, e.g. from the watch-later bookmark
         * @return `null` when the manifests cannot describe a playable pair
         */
        fun create(
            mediaId: String,
            manifests: DashManifestBuilder.Manifests,
            dataSourceFactory: DataSource.Factory,
            startPositionMs: Long = 0L,
            onVideoManifestParsed: ((DashMediaSource.ManifestParsedEvent) -> Unit)? = null,
        ): Created {
            val mediaItem = MediaItem.Builder()
                .setMediaId(mediaId)
                .setUri(dataUri(manifests.video))
                .setMimeType(androidx.media3.common.MimeTypes.APPLICATION_MPD)
                .setRequestMetadata(
                    MediaItem.RequestMetadata.Builder()
                        .setExtras(
                            Bundle().apply {
                                putString(VIDEO_MPD_KEY, manifests.video)
                                putString(AUDIO_MPD_KEY, manifests.audio)
                            },
                        )
                        .build(),
                )
                .setClippingConfiguration(
                    androidx.media3.common.ClippingConfiguration.Builder()
                        .setStartPositionMs(startPositionMs)
                        .build(),
                )
                .build()

            val merging = MergingMediaSource(
                dashFactory(dataSourceFactory, onVideoManifestParsed)
                    .createMediaSource(
                        MediaItem.Builder()
                            .setMediaId(mediaId)
                            .setUri(dataUri(manifests.video))
                            .setMimeType(androidx.media3.common.MimeTypes.APPLICATION_MPD)
                            .build(),
                    ),
                dashFactory(dataSourceFactory, null)
                    .createMediaSource(
                        MediaItem.Builder()
                            .setMediaId("$mediaId:audio")
                            .setUri(dataUri(manifests.audio))
                            .setMimeType(androidx.media3.common.MimeTypes.APPLICATION_MPD)
                            .build(),
                    ),
            )

            return Created(
                source = merging,
                mediaItem = mediaItem,
                videoFormats = manifests.videoFormats,
                audioFormats = manifests.audioFormats,
            )
        }

        private fun dashFactory(
            dataSourceFactory: DataSource.Factory,
            onManifestParsed: ((DashMediaSource.ManifestParsedEvent) -> Unit)?,
        ): DashMediaSource.Factory = DashMediaSource.Factory(dataSourceFactory).apply {
            if (onManifestParsed != null) setManifestParsedListener(onManifestParsed)
        }
    }
}

/** Marker so callers can recognise a merged DASH item in a playlist. */
@UnstableApi
object MergedDash {
    fun isMergedDash(item: MediaItem): Boolean =
        item.localConfiguration?.mimeType == androidx.media3.common.MimeTypes.APPLICATION_MPD &&
            item.requestMetadata.extras?.getString(MergingDashMediaSource.AUDIO_MPD_KEY) != null

    const val CONTENT_TYPE: Int = C.CONTENT_TYPE_DASH
}
