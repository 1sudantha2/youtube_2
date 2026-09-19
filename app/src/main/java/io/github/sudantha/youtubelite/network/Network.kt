package io.github.sudantha.youtubelite.network

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

/** Shared by API, extraction, images and playback. Credentials are never global interceptors. */
object Network {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(ConnectionPool(5, 2, TimeUnit.MINUTES))
            .dns(TtlDns())
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            // HTTP/2 and transparent gzip are OkHttp defaults. Never force Accept-Encoding.
            .build()
    }
}

private class TtlDns : Dns {
    private data class Entry(val addresses: List<InetAddress>, val expires: Long)
    private val cache = LinkedHashMap<String, Entry>()
    override fun lookup(hostname: String): List<InetAddress> {
        synchronized(cache) {
            cache[hostname]?.takeIf { it.expires > System.nanoTime() }?.let { return it.addresses }
        }
        val addresses = Dns.SYSTEM.lookup(hostname)
        synchronized(cache) {
            if (cache.size >= 32) cache.remove(cache.keys.first())
            cache[hostname] = Entry(addresses, System.nanoTime() + TimeUnit.MINUTES.toNanos(2))
        }
        return addresses
    }
}

suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response, onCancellation = { response.close() })
        }
    })
}
