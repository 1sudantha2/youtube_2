package app.you.tube

import android.app.Application
import app.you.tube.di.AppContainer
import app.you.tube.core.extract.NewPipeDownloader
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import org.schabi.newpipe.extractor.NewPipe

/**
 * Application entry point.
 *
 *  - Boots NewPipeExtractor once with the shared OkHttp downloader.
 *  - Installs a global Coil image pipeline tuned for low-RAM devices:
 *      * memory cache hard-capped at 20% of the app heap
 *      * 96 MB disk cache
 *      * no crossfade (avoids a per-image alpha compositing pass)
 */
class YouTubeApp : Application(), ImageLoaderFactory {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // NewPipe.init must run before any extractor usage — cheap (no I/O).
        NewPipe.init(container.newPipeDownloader)
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.20)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(96L * 1024 * 1024)
                    .build()
            }
            .respectCacheHeaders(false)
            .crossfade(false)
            .build()
}
