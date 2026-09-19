package com.youtubelite.app.ui.screens

import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.youtubelite.app.AppGraph
import kotlinx.coroutines.delay

private const val LOGIN_URL =
    "https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fwww.youtube.com%2F&hl=en"

/**
 * In-app Google sign-in. Polls the CookieManager on every finished page; as
 * soon as a SAPISID-bearing cookie set for youtube.com exists the session is
 * persisted (encrypted) and the WebView is torn down completely:
 * stopLoading -> detach -> destroy, releasing every native reference.
 */
@Composable
fun LoginScreen(onDone: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var captured by rememberSaveable { mutableStateOf(false) }

    val webView = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(true)
            cm.setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    val cookie = cm.getCookie("https://www.youtube.com")
                    if (cookie != null && AppGraph.auth.saveFromCookieString(cookie)) {
                        cm.flush()
                        captured = true
                    }
                }
            }
            loadUrl(LOGIN_URL)
        }
    }

    LaunchedEffect(captured) {
        if (captured) {
            delay(500)
            onDone()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webView.stopLoading()
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onDone) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                text = if (captured) "Signed in ✓" else "Sign in with your Google account",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
        }
        HorizontalDivider()
        AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize(), update = {})
    }
}
