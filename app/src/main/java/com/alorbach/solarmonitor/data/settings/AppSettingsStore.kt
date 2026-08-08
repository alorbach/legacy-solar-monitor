package com.alorbach.solarmonitor.data.settings

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.alorbach.solarmonitor.data.security.CredentialStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.io.IOException

data class AppSettings(
    val cloudBackupEnabled: Boolean = false,
    val gcsBucket: String = "",
    val gcsPrefix: String = "solar-monitor",
    val gcsSignedUrl: String = "",
)

class AppSettingsStore(
    context: Context,
    private val credentialStore: CredentialStore,
) {
    private val dataStore = PreferenceDataStoreFactory.create {
        context.preferencesDataStoreFile("settings.preferences_pb")
    }

    // toSettings reads the keystore-backed credential store, so the mapping must not run on the
    // collector's thread (the UI collects this flow).
    val settings: Flow<AppSettings> = dataStore.data
        .catch {
            if (it is IOException) emit(emptyPreferences()) else throw it
        }
        .map(::toSettings)
        .flowOn(Dispatchers.IO)

    suspend fun migrateLegacySecrets() {
        dataStore.edit { prefs ->
            migrateLegacySignedUrl(prefs)
        }
    }

    suspend fun update(transform: suspend (AppSettings) -> AppSettings) {
        dataStore.edit { prefs ->
            migrateLegacySignedUrl(prefs)
            val updated = transform(toSettings(prefs))
            prefs[Keys.cloudBackupEnabled] = updated.cloudBackupEnabled
            prefs[Keys.gcsBucket] = updated.gcsBucket
            prefs[Keys.gcsPrefix] = updated.gcsPrefix
            if (updated.gcsSignedUrl.isBlank()) {
                credentialStore.deleteNamed(CredentialStore.KEY_GCS_SIGNED_URL)
            } else {
                credentialStore.putNamed(CredentialStore.KEY_GCS_SIGNED_URL, updated.gcsSignedUrl)
            }
            prefs.remove(Keys.legacyGcsSignedUrl)
        }
    }

    private fun migrateLegacySignedUrl(prefs: MutablePreferences) {
        val legacy = prefs[Keys.legacyGcsSignedUrl]
        if (!legacy.isNullOrBlank()) {
            if (credentialStore.getNamed(CredentialStore.KEY_GCS_SIGNED_URL).isNullOrBlank()) {
                credentialStore.putNamed(CredentialStore.KEY_GCS_SIGNED_URL, legacy)
            }
            prefs.remove(Keys.legacyGcsSignedUrl)
        }
    }

    private fun toSettings(prefs: Preferences): AppSettings =
        AppSettings(
            cloudBackupEnabled = prefs[Keys.cloudBackupEnabled] ?: false,
            gcsBucket = prefs[Keys.gcsBucket] ?: "",
            gcsPrefix = prefs[Keys.gcsPrefix] ?: "solar-monitor",
            gcsSignedUrl = credentialStore.getNamed(CredentialStore.KEY_GCS_SIGNED_URL)
                ?: prefs[Keys.legacyGcsSignedUrl]
                ?: "",
        )

    private object Keys {
        val cloudBackupEnabled = booleanPreferencesKey("cloud_backup_enabled")
        val gcsBucket = stringPreferencesKey("gcs_bucket")
        val gcsPrefix = stringPreferencesKey("gcs_prefix")
        val legacyGcsSignedUrl = stringPreferencesKey("gcs_signed_url")
    }
}
