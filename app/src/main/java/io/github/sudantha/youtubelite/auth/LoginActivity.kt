package io.github.sudantha.youtubelite.auth

import android.annotation.SuppressLint
import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import io.github.sudantha.youtubelite.YouTubeApp
import io.github.sudantha.youtubelite.data.Feed
import io.github.sudantha.youtubelite.ui.YouTubeTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class LoginActivity : ComponentActivity() {
    private var webView: WebView? = null
    private var saving by mutableStateOf(false)
    private var message by mutableStateOf("Sign in with Google, then tap Finish. Google may block embedded browsers; guest playback remains available.")

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContent {
            YouTubeTheme {
                BackHandler {
                    val view = webView
                    if (view?.canGoBack() == true) view.goBack() else finish()
                }
                Column(Modifier.fillMaxSize().systemBarsPadding()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { finish() }) { Text("Cancel") }
                        TextButton(enabled = !saving, onClick = ::captureSession) { Text(if (saving) "Checking…" else "Finish") }
                    }
                    Text(message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
                    AndroidView(modifier = Modifier.weight(1f).fillMaxWidth(), factory = { context ->
                        WebView(context).apply {
                            webView = this
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            settings.setSupportMultipleWindows(false)
                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                    val url = request.url
                                    val allowed = url.scheme == "https" && url.host in setOf(
                                        "accounts.google.com", "myaccount.google.com", "www.google.com",
                                        "consent.google.com", "consent.youtube.com", "www.youtube.com", "youtube.com")
                                    if (!allowed && request.isForMainFrame) message = "This redirect was blocked for safety. You can cancel and continue as a guest."
                                    return !allowed
                                }
                                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                    if (request.isForMainFrame) message = "Sign-in page could not load. Check your connection and try again."
                                }
                                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                                    destroyWebView()
                                    message = "Sign-in browser closed. Cancel and try again."
                                    return true
                                }
                                // Default SSL error behavior is cancel; never bypass TLS validation.
                            }
                            loadUrl("https://accounts.google.com/ServiceLogin?service=youtube&continue=" + Uri.encode("https://www.youtube.com/"))
                        }
                    }, onRelease = { view -> if (webView === view) destroyWebView() })
                }
            }
        }
    }
    private fun captureSession() {
        val raw = CookieManager.getInstance().getCookie(SapisidHash.ORIGIN).orEmpty()
        if (SessionStore.fromCookies(raw) == null) {
            message = "No YouTube session found. Finish signing in, or use the app as a guest."
            return
        }
        saving = true
        lifecycleScope.launch {
            val app = application as YouTubeApp
            try {
                app.sessions.save(raw)
                app.api.feed(Feed.Subscriptions) // Verify account access, not merely cookie presence.
                CookieManager.getInstance().flush()
                destroyWebView()
                setResult(Activity.RESULT_OK)
                finish()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                app.sessions.clear()
                message = "Session verification failed. YouTube may block this sign-in method. Please try again or continue as a guest."
            } finally { saving = false }
        }
    }
    private fun destroyWebView() {
        webView?.let { view ->
            webView = null
            (view.parent as? ViewGroup)?.removeView(view)
            view.stopLoading()
            view.onPause()
            view.webChromeClient = null
            view.webViewClient = WebViewClient()
            view.removeAllViews()
            view.destroy()
        }
    }
    override fun onDestroy() { destroyWebView(); super.onDestroy() }
}
