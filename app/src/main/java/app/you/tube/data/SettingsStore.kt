package app.you.tube.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "you_tube_settings")

/** Featherweight Preferences DataStore for the handful of user settings. */
class SettingsStore(private val context: Context) {

    val defaultQuality: Flow<String> =
        context.settingsDataStore.data.map { it[KEY_QUALITY] ?: QUALITY_AUTO }

    val autoplay: Flow<Boolean> =
        context.settingsDataStore.data.map { it[KEY_AUTOPLAY] ?: true }

    suspend fun setDefaultQuality(quality: String) {
        context.settingsDataStore.edit { it[KEY_QUALITY] = quality }
    }

    suspend fun setAutoplay(value: Boolean) {
        context.settingsDataStore.edit { it[KEY_AUTOPLAY] = value }
    }

    companion object {
        const val QUALITY_AUTO = "auto"
        private val KEY_QUALITY = stringPreferencesKey("default_quality")
        private val KEY_AUTOPLAY = booleanPreferencesKey("autoplay")
    }
}
