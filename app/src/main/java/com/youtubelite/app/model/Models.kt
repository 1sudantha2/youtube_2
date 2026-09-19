package com.youtubelite.app.model

import androidx.compose.runtime.Immutable

/**
 * All UI models are @Immutable (enforced additionally via compose-stability.conf):
 * the Compose compiler can then prove every LazyColumn item is skippable, so a
 * scroll or progress update never recomposes unrelated rows (zero-jitter lists).
 */
@Immutable
data class Video(
    val id: String,
    val title: String,
    val channel: String,
    val channelId: String,
    val views: String,
    val published: String,
    val duration: String,
    val thumb: String,
    val isLive: Boolean = false,
)

@Immutable
data class Comment(
    val id: String,
    val author: String,
    val avatar: String,
    val text: String,
    val published: String,
    val likes: String,
)

@Immutable
data class QualityOption(
    val label: String,
    val height: Int,
    val url: String,
    val fps: Int,
)

/** All selectable rendition groups for one video. */
@Immutable
data class StreamSet(
    val qualities: List<QualityOption>, // video-only (adaptive), ascending by height
    val audioUrl: String?,              // best audio (Opus preferred)
    val muxed: List<QualityOption>,     // progressive (video+audio) fallbacks
)

@Immutable
data class WatchMeta(
    val videoId: String,
    val title: String,
    val channel: String,
    val channelId: String,
    val thumb: String,
    val views: String,
    val published: String,
    val likes: String,
    val description: String,
)

/** Target InnerTube browse feeds. */
enum class FeedSource(val browseId: String, val requiresAuth: Boolean, val title: String) {
    HOME("FEwhat_to_watch", false, "Home"),
    SUBSCRIPTIONS("FEsubscriptions", true, "Subscriptions"),
    LIBRARY("FElibrary", true, "Library"),
    HISTORY("FEhistory", true, "History"),
    LIKED("VLLM", true, "Liked videos"),
}
