package com.ultra.youtube.app.data.auth

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide, in-memory holder for the active session, persisted to a private
 * SharedPreferences file as a fallback for cold starts before the WebView is available.
 *
 * The cookies are *never* sent anywhere except google.com / youtube.com: the value is
 * attached only to InnerTube calls built by [com.ultra.youtube.app.data.innertube.InnerTubeClient].
 */
class CookieStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val _session = MutableStateFlow(load())
    val session: StateFlow<SessionCookies?> = _session.asStateFlow()

    val isAuthenticated: Boolean get() = _session.value?.isAuthenticated == true

    fun update(session: WebViewCookieExtractor.SessionCookies?) {
        if (session == _session.value) return
        _session.value = session
        prefs.edit {
            if (session == null) {
                remove(KEY)
            } else {
                putString(KEY, session.header)
            }
        }
    }

    fun clear() = update(null)

    private fun load(): WebViewCookieExtractor.SessionCookies? =
        WebViewCookieExtractor.parseCookieHeader(prefs.getString(KEY, null))

    private companion object {
        const val FILE = "yt_session"
        const val KEY = "cookie_header"
    }
}
