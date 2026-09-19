package com.youtubelite.app.extractor

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.create
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response

/** Bridges NewPipeExtractor to the app's pooled HTTP/2 OkHttp client. */
class OkHttpDownloader(private val client: OkHttpClient) : Downloader() {

    override fun execute(request: Request): Response {
        val builder = Request.Builder().url(request.url())
        when (request.httpMethod().uppercase()) {
            "POST" -> builder.post(
                create(null, request.dataToSend() ?: ByteArray(0))
            )
            "HEAD" -> builder.head()
            else -> builder.get()
        }
        for ((name, values) in request.headers()) {
            for (value in values) builder.header(name, value)
        }
        client.newCall(builder.build()).execute().use { response ->
            return Response(
                response.code,
                response.message,
                response.headers.toMultimap(),
                response.body?.string(),
                response.request.url.toString(),
            )
        }
    }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
