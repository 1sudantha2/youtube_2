package app.you.tube.core.extract

import app.you.tube.core.network.InnerTubeClient
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.util.Locale

/**
 * NewPipeExtractor transport backed by the app's single shared OkHttp client —
 * stream extraction rides the same HTTP/2 connection pool, DNS cache and
 * dispatcher as everything else (no extra sockets or thread pools).
 *
 * Per-request headers requested by the extractor (it impersonates specific
 * YouTube clients and their exact User-Agents) are preserved; a sensible UA is
 * injected only when none is provided.
 */
class NewPipeDownloader(private val client: OkHttpClient) : Downloader() {

    override fun execute(request: Request): Response {
        val method = request.httpMethod().uppercase(Locale.US)
        val payload = request.dataToSend()

        val body = when {
            payload != null -> payload.toRequestBody(null)
            method == "POST" || method == "PUT" || method == "PATCH" -> ByteArray(0).toRequestBody(null)
            else -> null
        }

        val builder = okhttp3.Request.Builder()
            .url(request.url())
            .method(method, body)

        var hasUserAgent = false
        request.headers().forEach { (name, values) ->
            for (value in values) {
                builder.addHeader(name, value)
                if (name.equals("User-Agent", ignoreCase = true)) hasUserAgent = true
            }
        }
        if (!hasUserAgent) builder.header("User-Agent", InnerTubeClient.USER_AGENT)

        client.newCall(builder.build()).execute().use { response ->
            if (response.code == 429) {
                throw ReCaptchaException(
                    "reCaptcha Challenge requested",
                    response.request.url.toString()
                )
            }
            val responseBody = response.body?.string().orEmpty()
            return Response(
                response.code,
                response.message,
                response.headers.toMultimap(),
                responseBody,
                response.request.url.toString()
            )
        }
    }
}
