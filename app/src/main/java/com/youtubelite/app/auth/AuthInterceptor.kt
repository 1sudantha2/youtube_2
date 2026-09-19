package com.youtubelite.app.auth

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Attaches the Google session to every *.youtube.com / *.google.com call:
 *   Cookie: <captured cookies>
 *   Authorization: SAPISIDHASH <ts>_<sha1>
 *   X-Origin / Origin headers required by InnerTube.
 *
 * Media hosts (googlevideo.com) are intentionally excluded — stream URLs are
 * already token-signed and cookies there only break CDN caching.
 */
class AuthInterceptor(private val auth: AuthRepository) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val host = request.url.host
        val authenticated = host.endsWith("youtube.com") || host.endsWith("google.com")
        if (!authenticated) return chain.proceed(request)

        val cookie = auth.cookieHeader() ?: return chain.proceed(request)
        val builder = request.newBuilder()
            .header("Cookie", cookie)

        auth.sapisid()?.let { sapisid ->
            builder
                .header("Authorization", SapisidHash.generate(sapisid))
                .header("X-Origin", "https://www.youtube.com")
                .header("Origin", "https://www.youtube.com")
        }
        return chain.proceed(builder.build())
    }
}
