package com.youtubelite.app

import android.app.Application
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.youtubelite.app.auth.AuthInterceptor
import com.youtubelite.app.auth.AuthRepository
import com.youtubelite.app.data.PrefsRepository
import com.youtubelite.app.extractor.OkHttpDownloader
import kotlinx.serialization.json.Json
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Protocol
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Hand-rolled dependency graph — no DI framework, no reflection, no codegen.
 * One graph, one process, singleton clients shared by every layer.
 */
object AppGraph {

    lateinit var json: Json
        private set
    lateinit var apiClient: OkHttpClient
        private set
    lateinit var mediaClient: OkHttpClient
        private set
    lateinit var imageLoader: ImageLoader
        private set
    lateinit var auth: AuthRepository
        private set
    lateinit var prefs: PrefsRepository
        private set

    fun init(app: Application) {
        json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        auth = AuthRepository(app)
        prefs = PrefsRepository(app)

        // Single shared client: HTTP/2 multiplexing + pooled keep-alive connections.
        apiClient = OkHttpClient.Builder()
            .connectionPool(ConnectionPool(6, 5, TimeUnit.MINUTES))
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .dispatcher(Dispatcher().apply {
                maxRequests = 64
                maxRequestsPerHost = 8
            })
            .addInterceptor(AuthInterceptor(auth))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()

        // Media client: long reads, no auth, no API cache (ExoPlayer owns range IO).
        mediaClient = OkHttpClient.Builder()
            .connectionPool(ConnectionPool(4, 5, TimeUnit.MINUTES))
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                        .build()
                )
            }
            .build()

        NewPipe.init(
            OkHttpDownloader(apiClient),
            Localization("en", "US"),
            ContentCountry("US"),
        )

        imageLoader = ImageLoader.Builder(app)
            .memoryCache { MemoryCache.Builder(app).maxSizePercent(0.20).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(File(app.cacheDir, "image_cache"))
                    .maxSizeBytes(128L * 1024 * 1024)
                    .build()
            }
            .allowHardware(true) // Bitmap.Config.HARDWARE — pixels live in GPU memory
            .crossfade(false)    // zero-alpha animations: no extra frames per image
            .respectCacheHeaders(false)
            .build()
    }
}
