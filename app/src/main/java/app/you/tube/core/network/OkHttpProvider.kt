package app.you.tube.core.network

import android.content.Context
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Single process-wide [OkHttpClient].
 *
 * - HTTP/2 multiplexing over a shared connection pool (default for HTTPS in OkHttp).
 * - DNS result cache: eliminates repeated lookups of the same handful of Google
 *   hostnames (video CDN, API, thumbnails) on every page load — a measurable
 *   latency win on slow-budget-device radios.
 * - Transparent GZIP (OkHttp's BridgeInterceptor adds Accept-Encoding and
 *   decompresses; large InnerTube payloads shrink ~10x over the wire).
 * - One dispatcher/thread pool shared by InnerTube calls, NewPipeExtractor and
 *   media streaming — no duplicated socket/thread overhead.
 */
object OkHttpProvider {

    @Volatile
    private var cached: OkHttpClient? = null

    fun get(context: Context): OkHttpClient =
        cached ?: synchronized(this) {
            cached ?: build(context.applicationContext).also { cached = it }
        }

    private fun build(appContext: Context): OkHttpClient {
        val dispatcher = Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 10
        }
        return OkHttpClient.Builder()
            .connectionPool(ConnectionPool(/* maxIdleConnections = */ 10, /* keepAlive = */ 5, TimeUnit.MINUTES))
            .dispatcher(dispatcher)
            .dns(CachingDns(Dns.SYSTEM))
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}

/**
 * Minimal DNS cache. Entries are effectively immortal — the hostnames this app
 * touches (googlevideo.com, youtube.com, ytimg.com, ggpht.com) are stable CDN
 * edges and the map stays in the low tens of entries, so eviction is unnecessary.
 * Failed lookups are not cached and fall through to [UnknownHostException].
 */
class CachingDns(private val delegate: Dns) : Dns {

    private val cache = ConcurrentHashMap<String, List<InetAddress>>(32)

    override fun lookup(hostname: String): List<InetAddress> {
        cache[hostname]?.let { return it }
        return try {
            delegate.lookup(hostname).also {
                if (it.isNotEmpty()) cache[hostname] = it
            }
        } catch (e: UnknownHostException) {
            throw e
        }
    }
}
