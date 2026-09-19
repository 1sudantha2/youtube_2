package app.you.tube.core.auth

import android.content.Context
import android.content.SharedPreferences
import android.webkit.CookieManager
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Session layer. Google cookies captured by the in-app WebView (SID, HSID,
 * SSID, APISID, SAPISID, LOGIN_INFO, __Secure-*...) are persisted in
 * EncryptedSharedPreferences and replayed as a raw `Cookie` header together
 * with a freshly computed `Authorization: SAPISIDHASH ...` header for
 * authenticated InnerTube calls (subscriptions feed, likes, comments...).
 */
class AuthManager(context: Context) {

    private val appContext = context.applicationContext

    private val prefs: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            @Suppress("DEPRECATION")
            EncryptedSharedPreferences.create(
                appContext,
                "yt_secure_auth",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (t: Throwable) {
            // Keystore corruption on some OEM devices — degrade to plain private
            // prefs rather than crash-looping the app.
            appContext.getSharedPreferences("yt_fallback_auth", Context.MODE_PRIVATE)
        }
    }

    val isLoggedIn: Boolean
        get() {
            val raw = cookieHeader() ?: return false
            return raw.contains("SAPISID") || raw.contains("LOGIN_INFO")
        }

    fun cookieHeader(): String? = prefs.getString(KEY_COOKIES, null)?.takeIf { it.isNotBlank() }

    fun sapisid(): String? =
        cookieHeader()
            ?.split(";")
            ?.firstOrNull { it.trim().startsWith("SAPISID=") }
            ?.substringAfter("=")
            ?.takeIf { it.isNotBlank() }

    /** `Authorization: SAPISIDHASH <ts>_<sha1>` — regenerated per call, exactly like the web client. */
    fun authorizationHeaderValue(): String? = sapisid()?.let { Sapisid.authorizationHeader(it) }

    /** Store the raw `CookieManager.getCookie("https://www.youtube.com")` string. */
    fun saveCookieHeader(raw: String) {
        val clean = raw.split(";").joinToString("; ") { it.trim() }
        prefs.edit().putString(KEY_COOKIES, clean).apply()
    }

    fun clearAll() {
        prefs.edit().clear().apply()
        runCatching {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        }
    }

    private companion object {
        const val KEY_COOKIES = "yt_cookie_header"
    }
}
