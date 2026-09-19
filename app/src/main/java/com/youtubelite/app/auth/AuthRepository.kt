package com.youtubelite.app.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Session cookie vault. Cookies never touch disk unencrypted:
 * EncryptedSharedPreferences (AES-256, Keystore-backed master key).
 */
class AuthRepository(context: Context) {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "yt_auth_vault",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    @Volatile
    private var cachedCookie: String? = null

    @Volatile
    private var cachedSapisid: String? = null

    val isSignedIn: Boolean
        get() = !cookieHeader().isNullOrEmpty()

    /** Full Cookie header value for youtube.com, or null when signed out. */
    fun cookieHeader(): String? {
        cachedCookie?.let { cached -> if (cached.isNotEmpty()) return cached }
        val stored = prefs.getString(KEY_COOKIE, null)
        cachedCookie = stored.orEmpty()
        return stored
    }

    fun sapisid(): String? {
        cachedSapisid?.let { return it }
        val parsed = parseSapisid(prefs.getString(KEY_SAPISID, null) ?: cookieHeader().orEmpty())
        cachedSapisid = parsed
        return parsed
    }

    /** Returns true when the cookie string carries a usable SAPISID. */
    fun saveFromCookieString(raw: String): Boolean {
        val sapisid = parseSapisid(raw) ?: return false
        prefs.edit()
            .putString(KEY_COOKIE, raw)
            .putString(KEY_SAPISID, sapisid)
            .apply()
        cachedCookie = raw
        cachedSapisid = sapisid
        return true
    }

    fun signOut() {
        prefs.edit().clear().apply()
        cachedCookie = null
        cachedSapisid = null
    }

    private fun parseSapisid(raw: String): String? =
        raw.split(";")
            .map { it.trim() }
            .firstOrNull {
                it.startsWith("SAPISID=") ||
                    it.startsWith("__Secure-3PAPISID=") ||
                    it.startsWith("__Secure-1PAPISID=")
            }
            ?.substringAfter('=')
            ?.takeIf { it.isNotBlank() }

    private companion object {
        const val KEY_COOKIE = "cookie"
        const val KEY_SAPISID = "sapisid"
    }
}
