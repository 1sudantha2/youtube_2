package com.ultra.youtube.app.data.auth

import android.annotation.SuppressLint
import android.os.Build
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Turns the browser's `Cookie:` header into typed session credentials.
 *
 * The parsing half ([parseCookieHeader]) is free of Android types so the rules can be
 * unit-tested on the JVM; the WebView glue simply feeds it [CookieManager.getCookie]
 * output.
 */
object WebViewCookieExtractor {

    /** Cookie names that must be present before we consider the user authenticated. */
    val REQUIRED_COOKIES = listOf("SID", "SAPISID")

    /** Names worth keeping when trimming a cookie header before it leaves the device. */
    val SESSION_COOKIE_WHITELIST = listOf(
        "SID", "SAPISID", "HSID", "SSID", "APISID", "LOGIN_INFO",
        "__Secure-3PAPISID", "__Secure-1PSID", "VISITOR_INFO1_LIVE", "PREF",
    )

    const val YOUTUBE_ORIGIN = "https://www.youtube.com"

    private const val ACCOUNTS_URL =
        "https://accounts.google.com/ServiceLogin?service=youtube" +
            "&continue=https%3A%2F%2Fwww.youtube.com%2F"

    /** Desktop UA: the mobile UA makes YouTube serve the reduced `m.` payloads. */
    const val DESKTOP_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    data class SessionCookies(
        /** Ready-to-send `Cookie:` header value. */
        val header: String,
        val cookies: Map<String, String>,
        val sapishid: String?,
        val sid: String?,
        val loginInfo: String?,
    ) {
        val isAuthenticated: Boolean get() = sapishid != null && sid != null

        /** Best-effort display identity. */
        val identityHint: String? get() = decodeLoginInfo(loginInfo) ?: sid?.take(10)
    }

    /**
     * Parses a `Cookie:` header.
     *
     * Handles the shapes browsers actually emit: `a=b; c=d`, `a=b;c=d`, empty values,
     * values containing `=`, and stray whitespace.
     */
    fun parseCookieHeader(header: String?): SessionCookies? {
        if (header.isNullOrBlank()) return null
        val cookies = LinkedHashMap<String, String>()
        for (part in header.split(';')) {
            val trimmed = part.trim()
            if (trimmed.isEmpty()) continue
            val eq = trimmed.indexOf('=')
            if (eq <= 0) continue
            val name = trimmed.substring(0, eq).trim()
            val value = trimmed.substring(eq + 1).trim()
            if (name.isEmpty()) continue
            cookies[name] = value
        }
        if (cookies.isEmpty()) return null
        return SessionCookies(
            header = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" },
            cookies = cookies,
            sapishid = cookies["SAPISID"] ?: cookies["__Secure-3PAPISID"],
            sid = cookies["SID"],
            loginInfo = cookies["LOGIN_INFO"],
        )
    }

    /** Reads the cookies the WebView currently holds for [url]. */
    fun extract(
        url: String = YOUTUBE_ORIGIN,
        cookieManager: CookieManager = CookieManager.getInstance(),
    ): SessionCookies? = parseCookieHeader(cookieManager.getCookie(url))

    fun isSignedIn(cookieManager: CookieManager = CookieManager.getInstance()): Boolean =
        extract(YOUTUBE_ORIGIN, cookieManager)?.isAuthenticated == true

    /**
     * `LOGIN_INFO` decodes to a protobuf-ish blob that contains the account email.
     * Returns the email when one is recognisable, otherwise `null`.
     */
    fun decodeLoginInfo(loginInfo: String?): String? {
        if (loginInfo.isNullOrBlank()) return null
        return runCatching {
            val decoded = android.util.Base64.decode(
                loginInfo,
                android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP,
            )
            String(decoded, Charsets.UTF_8)
                .split(Regex("[^\\x20-\\x7E]+"))
                .firstOrNull { EMAIL_REGEX.matches(it) }
        }.getOrNull()
    }

    private val EMAIL_REGEX = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")

    /**
     * Configures [webView] for the sign-in flow and emits [SessionCookies] as soon as the
     * required session cookies appear. Completes on its own; the caller only collects.
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun observeLogin(
        webView: WebView,
        startUrl: String = ACCOUNTS_URL,
    ): Flow<SessionCookies> = callbackFlow {
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cookieManager.setAcceptThirdPartyCookies(webView, true)
        }

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            userAgentString = DESKTOP_USER_AGENT
            cacheMode = WebSettings.LOAD_NO_CACHE
            setSupportZoom(false)
            builtInZoomControls = false
            mediaPlaybackRequiresUserGesture = false
        }
        webView.setBackgroundColor(0xFF0F0F0F.toInt())

        fun publish() {
            cookieManager.flush()
            extract(YOUTUBE_ORIGIN, cookieManager)
                ?.takeIf { it.isAuthenticated }
                ?.let { trySend(it) }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val host = request.url?.host.orEmpty()
                if (host.endsWith("youtube.com")) publish()
                return false
            }

            override fun onPageFinished(view: WebView, url: String) {
                publish()
            }
        }

        webView.loadUrl(startUrl)

        awaitClose {
            webView.stopLoading()
            webView.webViewClient = WebViewClient()
        }
    }
}
