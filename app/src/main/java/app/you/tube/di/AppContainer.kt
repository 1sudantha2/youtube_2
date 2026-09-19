package app.you.tube.di

import android.content.Context
import app.you.tube.core.auth.AuthManager
import app.you.tube.core.extract.NewPipeDownloader
import app.you.tube.core.extract.StreamRepository
import app.you.tube.core.network.InnerTubeClient
import app.you.tube.core.network.OkHttpProvider
import app.you.tube.data.SettingsStore
import app.you.tube.data.YouTubeRepository
import app.you.tube.player.PlayerConnection
import okhttp3.OkHttpClient

/**
 * Hand-rolled singleton container (no DI framework — zero codegen, zero
 * startup reflection cost, smaller APK). Everything is lazy: the app process
 * cold-start path only touches what it actually needs.
 */
class AppContainer(context: Context) {

    val appContext: Context = context.applicationContext

    val okHttp: OkHttpClient by lazy { OkHttpProvider.get(appContext) }

    val auth: AuthManager by lazy { AuthManager(appContext) }

    val settings: SettingsStore by lazy { SettingsStore(appContext) }

    private val innerTube: InnerTubeClient by lazy { InnerTubeClient(okHttp, auth) }

    private val streamRepository: StreamRepository by lazy { StreamRepository(okHttp) }

    val repository: YouTubeRepository by lazy { YouTubeRepository(innerTube, streamRepository) }

    val newPipeDownloader: NewPipeDownloader by lazy { NewPipeDownloader(okHttp) }

    val playerConnection: PlayerConnection by lazy { PlayerConnection(appContext) }
}
