package com.youtubelite.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.youtubelite.app.player.PlaybackHub

/**
 * Application entry. All singletons (HTTP/2 clients, Coil image loader with
 * HARDWARE bitmaps, extractor runtime) are wired once here — cold-start does
 * zero reflective service lookup.
 */
class YouApp : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
        PlaybackHub.init(this)
        val channel = NotificationChannel(
            CHANNEL_PLAYBACK,
            "Playback",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Background audio playback" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun newImageLoader(): ImageLoader = AppGraph.imageLoader

    private companion object {
        const val CHANNEL_PLAYBACK = "playback"
    }
}
