package com.ultra.youtube.app.ui.screens

import android.annotation.SuppressLint
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.ultra.youtube.app.data.auth.WebViewCookieExtractor
import com.ultra.youtube.app.ui.theme.AppColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

/**
 * In-app Google sign-in.
 *
 * The WebView is an ordinary browser session: the user enters their password on Google's
 * own page, and when the session cookies land, [WebViewCookieExtractor.observeLogin] picks
 * them out of the cookie jar and hands them to
 * [com.ultra.youtube.app.data.auth.CookieStore].
 *
 * Nothing is sent anywhere but youtube.com/google.com — the cookies stay on the device and
 * are attached only to requests this app makes itself.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginScreen(
    onSignedIn: (WebViewCookieExtractor.SessionCookies) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val webView = remember { WebView(context) }

    DisposableEffect(webView) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope.launch {
            WebViewCookieExtractor.observeLogin(webView)
                .catch { /* the flow simply never emits */ }
                .collect { session -> onSignedIn(session) }
        }
        onDispose {
            scope.cancel()
            webView.stopLoading()
            webView.destroy()
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Text(
            text = "Sign in to YouTube",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(16.dp),
        )
        Text(
            text = "Cookies are read from this WebView and never leave the device.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(AppColors.SurfaceDark),
        ) {
            AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
        }
    }
}
