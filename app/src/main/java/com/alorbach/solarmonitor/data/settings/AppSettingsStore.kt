package com.alorbach.solarmonitor.data.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

data class AppSettings(
    val cloudBackupEnabled: Boolean = false,
    val gcsBucket: String = "",
    val gcsPrefix: String = "solar-monitor",
    val gcsSignedUrl: String = "",
)

class AppSettingsStore(context: Context) {
    private val dataStore = PreferenceDataStoreFactory.create {
        context.preferencesDataStoreFile("settings.preferences_pb")
    }

    val settings: Flow<AppSettings> = dataStore.data
        .catch {
            if (it is IOException) emit(emptyPreferences()) else throw it
        }
        .map(::toSettings)

    suspend fun update(transform: suspend (AppSettings) -> AppSettings) {
        dataStore.edit { prefs ->
            val updated = transform(toSettings(prefs))
            prefs[Keys.cloudBackupEnabled] = updated.cloudBackupEnabled
            prefs[Keys.gcsBucket] = updated.gcsBucket
            prefs[Keys.gcsPrefix] = updated.gcsPrefix
            prefs[Keys.gcsSignedUrl] = updated.gcsSignedUrl
        }
    }

    private fun toSettings(prefs: Preferences): AppSettings =
        AppSettings(
            cloudBackupEnabled = prefs[Keys.cloudBackupEnabled] ?: false,
            gcsBucket = prefs[Keys.gcsBucket] ?: "",
            gcsPrefix = prefs[Keys.gcsPrefix] ?: "solar-monitor",
            gcsSignedUrl = prefs[Keys.gcsSignedUrl] ?: "",
        )

    private object Keys {
        val cloudBackupEnabled = booleanPreferencesKey("cloud_backup_enabled")
        val gcsBucket = stringPreferencesKey("gcs_bucket")
        val gcsPrefix = stringPreferencesKey("gcs_prefix")
        val gcsSignedUrl = stringPreferencesKey("gcs_signed_url")
    }
}
