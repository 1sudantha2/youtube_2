package com.youtubelite.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "yt_prefs")

/** Lightweight preference store (no SQLite, no SharedPreferences overhead). */
class PrefsRepository(private val context: Context) {

    private object Keys {
        val DEFAULT_QUALITY = stringPreferencesKey("default_quality")
        val AUDIO_ONLY_DEFAULT = booleanPreferencesKey("audio_only_default")
    }

    /** "auto" or a quality label like "720p". */
    val defaultQuality: Flow<String> =
        context.dataStore.data.map { it[Keys.DEFAULT_QUALITY] ?: "auto" }

    val audioOnlyDefault: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.AUDIO_ONLY_DEFAULT] ?: false }

    suspend fun setDefaultQuality(label: String) {
        context.dataStore.edit { it[Keys.DEFAULT_QUALITY] = label }
    }

    suspend fun setAudioOnlyDefault(on: Boolean) {
        context.dataStore.edit { it[Keys.AUDIO_ONLY_DEFAULT] = on }
    }
}
