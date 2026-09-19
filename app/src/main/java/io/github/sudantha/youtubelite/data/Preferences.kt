package io.github.sudantha.youtubelite.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map

private val Context.settings by preferencesDataStore("settings")
class Preferences(private val context: Context) {
    private val backgroundKey = booleanPreferencesKey("background_audio")
    val backgroundAudio = context.settings.data.map { it[backgroundKey] ?: true }
    suspend fun setBackgroundAudio(enabled: Boolean) { context.settings.edit { it[backgroundKey] = enabled } }
}
