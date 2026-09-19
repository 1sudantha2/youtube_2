package io.github.sudantha.youtubelite.auth

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Immutable
data class Session(val cookieHeader: String, val sapisid: String)

/** Initialize and access disk on IO. Never put credentials in DataStore, logs or backups. */
class SessionStore(private val context: Context) {
    private val lock = Mutex()
    private val mutable = MutableStateFlow<Session?>(null)
    val session = mutable.asStateFlow()
    private val prefs by lazy {
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(context, "youtube_session", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    }
    private var loaded = false
    suspend fun load() = withContext(Dispatchers.IO) {
        lock.withLock {
            if (!loaded) {
                mutable.value = prefs.getString("cookies", null)?.let(::fromCookies)
                loaded = true
            }
        }
    }
    suspend fun save(raw: String) = withContext(Dispatchers.IO) {
        val session = requireNotNull(fromCookies(raw)) { "No YouTube session was found." }
        lock.withLock {
            check(prefs.edit().putString("cookies", session.cookieHeader).commit()) { "Could not securely save session" }
            mutable.value = session
            loaded = true
        }
    }
    suspend fun clear() = withContext(Dispatchers.IO) {
        lock.withLock {
            check(prefs.edit().clear().commit())
            mutable.value = null
            loaded = true
        }
    }
    companion object {
        fun fromCookies(raw: String): Session? {
            val cookies = SapisidHash.cookies(raw)
            val key = cookies["SAPISID"] ?: cookies["__Secure-3PAPISID"] ?: return null
            return Session(cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }, key)
        }
    }
}
