package io.github.sudantha.youtubelite

import android.app.Application
import android.content.ComponentCallbacks2
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import io.github.sudantha.youtubelite.auth.SessionStore
import io.github.sudantha.youtubelite.data.InnerTube
import io.github.sudantha.youtubelite.data.Preferences
import io.github.sudantha.youtubelite.network.Network
import io.github.sudantha.youtubelite.player.PlaybackEngine

class YouTubeApp : Application(), ImageLoaderFactory {
    val sessions by lazy { SessionStore(this) }
    val preferences by lazy { Preferences(this) }
    val api by lazy { InnerTube(Network.client, sessions) }
    val engine by lazy { PlaybackEngine(this, Network.client) }
    private var images: ImageLoader? = null
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient(Network.client)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.12).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("thumbnails")).maxSizeBytes(32L * 1024 * 1024).build() }
        .allowHardware(true).crossfade(false).respectCacheHeaders(true)
        .build().also { images = it }
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) images?.memoryCache?.clear()
    }
}
