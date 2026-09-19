package app.you.tube.ui.auth

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.you.tube.core.network.InnerTubeClient
import app.you.tube.ui.LocalAppContainer

/**
 * In-app WebView sign-in (Module A).
 *
 *  - Loads the Google account sign-in flow with `service=youtube`, so the
 *    session cookies (SID, HSID, SSID, APISID, SAPISID, LOGIN_INFO, __Secure-*)
 *    land on the youtube.com domain, where `CookieManager.getCookie()` can
 *    read them.
 *  - A desktop-Chrome User-Agent is presented (embedded WebView UAs are
 *    sometimes blocked by the sign-in flow).
 *  - On capture, the raw cookie string is persisted (encrypted) and the
 *    SAPISIDHASH authorization header can be derived for every authenticated
 *    InnerTube call.
 *  - The WebView instance is fully destroyed on disposal (`onRelease`):
 *    about:blank → onPause → destroy, releasing its renderer memory.
 */
@Composable
fun LoginScreen(onDone: () -> Unit) {
    val container = LocalAppContainer.current
    var signingIn by remember { mutableStateOf(true) }
    var captured by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        Row(
            Modifier.fillMaxWidth().padding(end = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onDone) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                "Sign in",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }
        Text(
            "Sign in with your Google account to enable subscriptions, likes and comments. " +
                "Credentials are stored only on this device.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
        )

        Box(Modifier.fillMaxSize()) {
            WebViewAuth(
                onCookiesCaptured = { cookies ->
                    if (!captured) {
                        captured = true
                        container.auth.saveCookieHeader(cookies)
                        signingIn = false
                        onDone()
                    }
                },
                onLoading = { signingIn = it }
            )
            if (signingIn) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
        }
    }
}

private const val LOGIN_URL =
    "https://accounts.google.com/ServiceLogin?service=youtube&" +
        "continue=https%3A%2F%2Fwww.youtube.com%2Fsignin%3Fnext%3D%252F&hl=en&passive=true&uilel=3"

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WebViewAuth(
    onCookiesCaptured: (String) -> Unit,
    onLoading: (Boolean) -> Unit
) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
            }
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.setSupportZoom(false)
                settings.displayZoomControls = false
                settings.mediaPlaybackRequiresUserGesture = true
                // Desktop UA — embedded-WebView UAs can be rejected by sign-in.
                settings.userAgentString = InnerTubeClient.USER_AGENT

                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest
                    ): Boolean {
                        val url = request.url.toString()
                        val allowed = url.startsWith("https://accounts.google.com") ||
                            url.startsWith("https://www.google.com") ||
                            url.startsWith("https://youtube.com") ||
                            url.startsWith("https://www.youtube.com") ||
                            url.startsWith("https://m.youtube.com") ||
                            url.startsWith("https://myaccount.google.com") ||
                            url.startsWith("about:")
                        return !allowed
                    }

                    override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                        onLoading(true)
                        // Poll on navigation — cookies for youtube.com appear once
                        // sign-in completes and the flow redirects back.
                        val cookies = CookieManager.getInstance().getCookie("https://www.youtube.com")
                        if (cookies != null && cookies.contains("LOGIN_INFO") && cookies.contains("SAPISID")) {
                            CookieManager.getInstance().flush()
                            onCookiesCaptured(cookies)
                        }
                        super.onPageStarted(view, url, favicon)
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        onLoading(false)
                        val cookies = CookieManager.getInstance().getCookie("https://www.youtube.com")
                        if (cookies != null && cookies.contains("LOGIN_INFO") && cookies.contains("SAPISID")) {
                            CookieManager.getInstance().flush()
                            onCookiesCaptured(cookies)
                        }
                    }
                }
                loadUrl(LOGIN_URL)
            }
        },
        onRelease = { webView ->
            // Destroy the WebView completely once cookies are captured / user leaves.
            runCatching {
                webView.stopLoading()
                webView.loadUrl("about:blank")
                webView.onPause()
                webView.clearHistory()
                webView.webViewClient = WebViewClient()
                webView.removeAllViews()
                webView.destroy()
            }
        }
    )
}
