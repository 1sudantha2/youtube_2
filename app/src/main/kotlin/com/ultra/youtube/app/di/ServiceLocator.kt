package com.ultra.youtube.app.di

import android.content.Context
import com.ultra.youtube.app.data.YoutubeRepository
import com.ultra.youtube.app.data.auth.CookieStore
import com.ultra.youtube.app.data.innertube.InnerTubeClient
import okhttp3.OkHttpClient

/**
 * Hand-rolled dependency graph.
 *
 * Deliberately no DI framework: Hilt/Dagger add an annotation processor, generated code
 * and reflection — all of which cost DEX size and cold-start time in an app whose entire
 * budget is "tiny". Four singletons, created lazily, are enough.
 */
class ServiceLocator private constructor(context: Context) {

    private val appContext = context.applicationContext

    val cookieStore: CookieStore by lazy { CookieStore(appContext) }

    /** One OkHttp instance for the whole app: one connection pool, one dispatcher. */
    val http: OkHttpClient by lazy { InnerTubeClient.defaultHttp() }

    val innerTube: InnerTubeClient by lazy {
        InnerTubeClient(http, InnerTubeClient.AuthProvider { cookieStore.session.value })
    }

    val repository: YoutubeRepository by lazy { YoutubeRepository(innerTube, cookieStore) }

    companion object {
        @Volatile
        private var instance: ServiceLocator? = null

        fun get(context: Context): ServiceLocator =
            instance ?: synchronized(this) {
                instance ?: ServiceLocator(context).also { instance = it }
            }
    }
}
