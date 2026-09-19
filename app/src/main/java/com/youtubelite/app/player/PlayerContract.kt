package com.youtubelite.app.player

/** Shared keys for UI <-> PlaybackService session commands. */
object PlayerContract {
    const val CMD_PLAY = "yl.play"
    const val CMD_QUALITY = "yl.quality"
    const val CMD_AUTO = "yl.auto"
    const val CMD_AUDIO_ONLY = "yl.audio_only"
    const val CMD_STOP = "yl.stop"

    const val EX_ID = "id"
    const val EX_TITLE = "title"
    const val EX_CHANNEL = "channel"
    const val EX_THUMB = "thumb"
    const val EX_AUDIO = "audio"
    const val EX_QUALITIES = "qualities"
    const val EX_MUXED = "muxed"
    const val EX_AUTO = "auto"
    const val EX_LABEL = "label"
    const val EX_ON = "on"

    const val EXTRA_ACTIVE_LABEL = "yl.active_label"
    const val EXTRA_AUTO = "yl.is_auto"
}
