package com.ultra.youtube.app.ui

import android.content.Context
import android.os.Build
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import com.ultra.youtube.app.data.auth.WebViewCookieExtractor
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Coil configuration for a memory-starved device.
 *
 * The important part is `allowHardware(true)`: on API 26+ Coil decodes into
 * `Bitmap.Config.HARDWARE`, which lives in GPU memory and is **not** counted against the
 * app's Java heap. A 1080p ARGB_8888 thumbnail is ~8 MB of heap; the same pixels as a
 * hardware bitmap cost the app ~0 heap. That single flag is the difference between a feed
 * that scrolls and a feed that OOMs.
 *
 * Caches are sized from the *memory class*, not from a constant, so the same build behaves
 * sanely on a 2 GB phone and a 16 GB tablet.
 */
object CoilConfig {

    /** Share one OkHttp client with the InnerTube transport: one connection pool. */
    fun build(
        context: Context,
        http: OkHttpClient? = null,
    ): ImageLoader {
        val appContext = context.applicationContext
        val memoryClassMb = (appContext.getSystemService(Context.ACTIVITY_SERVICE)
            as? android.app.ActivityManager)?.memoryClass ?: 128

        return ImageLoader.Builder(appContext)
            .apply { http?.let { okHttp(it) } }
            // HARDWARE bitmaps: pixels live in GPU memory, off the Java heap.
            .allowHardware(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            .allowRgb565(true)
            .respectCacheHeaders(false)
            .crossfade(false)
            .memoryCache {
                MemoryCache.Builder(appContext)
                    // ~10% of the heap ceiling, hard-capped at 20 MB.
                    .maxSizePercent(0.10)
                    .maxSizeBytes(minOf(20L * 1024 * 1024, memoryClassMb * 1024L * 1024L / 10))
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(appContext.cacheDir.resolve("image_cache"))
                    .maxSizeBytes(60L * 1024 * 1024)
                    .build()
            }
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(CachePolicy.ENABLED)
            .components {
                // Thumbnails are static; no need for the animated-image decoders.
            }
            .build()
    }

    /**
     * Image loader with the session cookie attached — needed for channel avatars that
     * YouTube serves from a signed URL.
     */
    fun authenticated(context: Context, http: OkHttpClient, cookieHeader: String?): OkHttpClient {
        if (cookieHeader.isNullOrBlank()) return http
        return http.newBuilder()
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("Cookie", cookieHeader)
                        .header("User-Agent", WebViewCookieExtractor.DESKTOP_USER_AGENT)
                        .build(),
                )
            }
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
