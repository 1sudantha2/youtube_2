package com.ultra.youtube.app.player

import com.ultra.youtube.app.domain.Format

/**
 * Builds a tiny on-the-fly DASH MPD from InnerTube's `streamingData`.
 *
 * InnerTube hands back *individual* DASH segment streams (one `url` per itag, each with
 * `initRange` + `indexRange`). Media3 can only merge two DASH sources when each side is a
 * real MPD, so we synthesise one per media type:
 *
 *  - **video manifest**: one `Representation` per resolution, so the in-player quality
 *    picker can switch renditions without re-preparing the player;
 *  - **audio manifest**: the single best-bitrate audio stream.
 *
 * The two manifests are then fed to [MergingDashMediaSource], a `MergingMediaSource` of
 * two `DashMediaSource`s — the classic DASH video/audio merge.
 *
 * The manifest is pure UTF-8 XML with no Android dependencies, so it is unit-testable.
 */
object DashManifestBuilder {

    private const val MIN_DURATION_S = 1.0

    data class Manifests(
        val video: String,
        val audio: String,
        val videoFormats: List<Format>,
        val audioFormats: List<Format>,
    )

    /**
     * @return `null` when no DASH-describable video *and* audio stream exist (the caller
     *   then falls back to progressive playback).
     */
    fun build(formats: List<Format>, durationMs: Long): Manifests? {
        val usable = formats.filter { it.url != null }
        // Only on-demand DASH segments (those exposing an sidx index) can be described in
        // an MPD; progressive MP4s are played directly instead.
        val video = usable
            .filter { it.isVideo && it.hasIndexRange() }
            .sortedWith(compareByDescending<Format> { it.height }.thenByDescending { it.bitrate })
            .distinctBy { it.height to it.fps }
        val audio = usable
            .filter { it.isAudio && it.hasIndexRange() }
            .sortedByDescending { it.bitrate }
            .take(1)

        if (video.isEmpty() || audio.isEmpty()) return null

        val durationSeconds = (durationMs / 1000.0).coerceAtLeast(MIN_DURATION_S)
        return Manifests(
            video = render(video, durationSeconds),
            audio = render(audio, durationSeconds),
            videoFormats = video,
            audioFormats = audio,
        )
    }

    private fun render(formats: List<Format>, durationSeconds: Double): String {
        val isVideo = formats.first().isVideo
        val sb = StringBuilder(2048)
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            .append("<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" ")
            .append("profiles=\"urn:mpeg:dash:profile:full:2011\" ")
            .append("type=\"static\" ")
            .append("mediaPresentationDuration=\"PT").append(formatDuration(durationSeconds)).append("S\" ")
            .append("minBufferTime=\"PT1.5S\">\n")
            .append("  <Period start=\"PT0S\">\n")
            .append("    <AdaptationSet mimeType=\"").append(formats.first().mimeType).append("\" ")
            .append(if (isVideo) "subsegmentAlignment=\"true\" subsegmentStartsWithSAP=\"1\"" else "lang=\"en\"")
            .append(">\n")

        for (format in formats) {
            sb.append("      <Representation id=\"").append(format.itag).append("\" ")
                .append("bandwidth=\"").append(format.bitrate).append("\" ")
                .append("codecs=\"").append(escape(codecsOf(format))).append("\" ")
            if (isVideo) {
                sb.append("width=\"").append(format.width).append("\" ")
                    .append("height=\"").append(format.height).append("\" ")
                    .append("frameRate=\"").append(format.fps.coerceAtLeast(1)).append("\" ")
            } else {
                sb.append("audioSamplingRate=\"").append(format.audioSampleRate).append("\" ")
            }
            sb.append(">\n")
                // Media3 resolves relative segment URIs against BaseURL; InnerTube already
                // returns absolute googlevideo URLs, so this is a pass-through.
                .append("        <BaseURL>").append(escape(format.url!!)).append("</BaseURL>\n")
                .append("        <SegmentBase indexRange=\"")
                .append(format.indexRangeStart).append('-').append(format.indexRangeEnd)
                .append("\">\n")
                .append("          <Initialization range=\"")
                .append(format.initRangeStart).append('-').append(format.initRangeEnd)
                .append("\" />\n")
                .append("        </SegmentBase>\n")
                .append("      </Representation>\n")
        }

        sb.append("    </AdaptationSet>\n")
            .append("  </Period>\n")
            .append("</MPD>\n")
        return sb.toString()
    }

    /** `codecs="avc1.640028"` from the raw `mimeType; codecs="…"` string, with a default. */
    fun codecsOf(format: Format): String {
        if (format.codecs.isNotBlank()) return format.codecs
        return if (format.isVideo) "avc1.4D401E" else "mp4a.40.2"
    }

    private val CODECS_REGEX = Regex("codecs=\"([^\"]+)\"")

    /** Extracts the codec string from a raw InnerTube `mimeType` value. */
    fun parseCodecs(rawMimeType: String): String =
        CODECS_REGEX.find(rawMimeType)?.groupValues?.getOrNull(1).orEmpty()

    private fun Format.hasIndexRange(): Boolean =
        indexRangeStart != Format.UNSET && indexRangeEnd != Format.UNSET &&
            initRangeStart != Format.UNSET && initRangeEnd != Format.UNSET

    /** `90.5` -> `90.500`; keeps the MPD `PT…S` grammar unambiguous. */
    private fun formatDuration(seconds: Double): String =
        String.format(java.util.Locale.US, "%.3f", seconds)

    private fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
