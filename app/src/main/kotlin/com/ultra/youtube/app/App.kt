package com.ultra.youtube.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.ultra.youtube.app.di.ServiceLocator
import com.ultra.youtube.app.ui.CoilConfig

/**
 * Application entry point.
 *
 * Implements [ImageLoaderFactory] so Coil picks up the shared, memory-capped image loader
 * ([CoilConfig.build]) instead of constructing a default one with a much larger cache.
 */
class App : Application(), ImageLoaderFactory {

    val locator: ServiceLocator by lazy { ServiceLocator.get(this) }

    override fun onCreate() {
        super.onCreate()
        // Touch the WebView cookie jar early: it warms the cookie store the sign-in flow
        // and every authenticated request depend on.
        runCatching { android.webkit.CookieManager.getInstance() }
        createNotificationChannel()
    }

    override fun newImageLoader(): ImageLoader = CoilConfig.build(this)

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(PLAYBACK_CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                PLAYBACK_CHANNEL_ID,
                "Playback",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Now playing controls"
                setShowBadge(false)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            },
        )
    }

    companion object {
        const val PLAYBACK_CHANNEL_ID = "playback"
    }
}
