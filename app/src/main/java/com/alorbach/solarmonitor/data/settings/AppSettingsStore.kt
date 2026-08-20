package com.alorbach.solarmonitor.data.settings

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.alorbach.solarmonitor.data.model.StatsGranularity
import com.alorbach.solarmonitor.data.security.CredentialStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

data class AppSettings(
    val cloudBackupEnabled: Boolean = false,
    val gcsBucket: String = "",
    val gcsPrefix: String = "solar-monitor",
    val gcsSignedUrl: String = "",
    val backupIncludeDatabase: Boolean = true,
    val backupIncludeImportCopies: Boolean = true,
    val backupLastAttemptEpochSeconds: Long? = null,
    val backupLastSuccessEpochSeconds: Long? = null,
    val backupLastMessage: String = "",
    val backupLastOk: Boolean? = null,
    val statsGranularity: StatsGranularity = StatsGranularity.DAY,
    val statsSelectedDeviceId: Long? = null,
    /** Empty string means follow the system locale. */
    val languageTag: String = "",
    val hourAggregatesBackfilled: Boolean = false,
    /** Bump to force a full hour-aggregate recompute after aggregator/parser fixes. */
    val hourAggregatesSchemaVersion: Int = 0,
    /** Seconds between live Bluetooth polls while the foreground service runs. */
    val livePollIntervalSeconds: Long = 60,
    /** Home-screen compact/medium widgets; null = first device by name. */
    val widgetDeviceId: Long? = null,
    /** Signed GET URL for restoring solar-monitor.db (method-specific; PUT cannot download). */
    val gcsSignedGetUrl: String = "",
    /** Notify on new inverter WARNING events from the last 24 hours. */
    val inverterWarningAlertsEnabled: Boolean = true,
    /** Per-device last notified event entryId, encoded as "id:entryId,id:entryId". */
    val eventAlertWatermarks: String = "",
)

class AppSettingsStore(
    context: Context,
    private val credentialStore: CredentialStore,
) {
    private val dataStore = PreferenceDataStoreFactory.create {
        context.preferencesDataStoreFile("settings.preferences_pb")
    }

    val settings: Flow<AppSettings> = dataStore.data
        .catch {
            if (it is IOException) emit(emptyPreferences()) else throw it
        }
        .map(::toSettings)

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
            if (updated.gcsSignedGetUrl.isBlank()) {
                credentialStore.deleteNamed(CredentialStore.KEY_GCS_SIGNED_GET_URL)
            } else {
                credentialStore.putNamed(CredentialStore.KEY_GCS_SIGNED_GET_URL, updated.gcsSignedGetUrl)
            }
            prefs[Keys.backupIncludeDatabase] = updated.backupIncludeDatabase
            prefs[Keys.backupIncludeImportCopies] = updated.backupIncludeImportCopies
            if (updated.backupLastAttemptEpochSeconds == null) {
                prefs.remove(Keys.backupLastAttemptEpochSeconds)
            } else {
                prefs[Keys.backupLastAttemptEpochSeconds] = updated.backupLastAttemptEpochSeconds
            }
            if (updated.backupLastSuccessEpochSeconds == null) {
                prefs.remove(Keys.backupLastSuccessEpochSeconds)
            } else {
                prefs[Keys.backupLastSuccessEpochSeconds] = updated.backupLastSuccessEpochSeconds
            }
            prefs[Keys.backupLastMessage] = updated.backupLastMessage
            when (updated.backupLastOk) {
                null -> prefs.remove(Keys.backupLastOk)
                true -> prefs[Keys.backupLastOk] = true
                false -> prefs[Keys.backupLastOk] = false
            }
            prefs[Keys.statsGranularity] = updated.statsGranularity.name
            if (updated.statsSelectedDeviceId == null) {
                prefs.remove(Keys.statsSelectedDeviceId)
            } else {
                prefs[Keys.statsSelectedDeviceId] = updated.statsSelectedDeviceId
            }
            prefs[Keys.languageTag] = updated.languageTag
            prefs[Keys.hourAggregatesBackfilled] = updated.hourAggregatesBackfilled
            prefs[Keys.hourAggregatesSchemaVersion] = updated.hourAggregatesSchemaVersion
            prefs[Keys.livePollIntervalSeconds] = updated.livePollIntervalSeconds.coerceIn(15L, 3600L)
            if (updated.widgetDeviceId == null) {
                prefs.remove(Keys.widgetDeviceId)
            } else {
                prefs[Keys.widgetDeviceId] = updated.widgetDeviceId
            }
            prefs[Keys.inverterWarningAlertsEnabled] = updated.inverterWarningAlertsEnabled
            prefs[Keys.eventAlertWatermarks] = updated.eventAlertWatermarks
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
            backupIncludeDatabase = prefs[Keys.backupIncludeDatabase] ?: true,
            backupIncludeImportCopies = prefs[Keys.backupIncludeImportCopies] ?: true,
            backupLastAttemptEpochSeconds = prefs[Keys.backupLastAttemptEpochSeconds],
            backupLastSuccessEpochSeconds = prefs[Keys.backupLastSuccessEpochSeconds],
            backupLastMessage = prefs[Keys.backupLastMessage] ?: "",
            backupLastOk = prefs[Keys.backupLastOk],
            statsGranularity = prefs[Keys.statsGranularity]
                ?.let { runCatching { StatsGranularity.valueOf(it) }.getOrNull() }
                ?: StatsGranularity.DAY,
            statsSelectedDeviceId = prefs[Keys.statsSelectedDeviceId],
            languageTag = prefs[Keys.languageTag] ?: "",
            hourAggregatesBackfilled = prefs[Keys.hourAggregatesBackfilled] ?: false,
            hourAggregatesSchemaVersion = prefs[Keys.hourAggregatesSchemaVersion] ?: 0,
            livePollIntervalSeconds = prefs[Keys.livePollIntervalSeconds] ?: 60L,
            widgetDeviceId = prefs[Keys.widgetDeviceId],
            gcsSignedGetUrl = credentialStore.getNamed(CredentialStore.KEY_GCS_SIGNED_GET_URL) ?: "",
            inverterWarningAlertsEnabled = prefs[Keys.inverterWarningAlertsEnabled] ?: true,
            eventAlertWatermarks = prefs[Keys.eventAlertWatermarks] ?: "",
        )

    private object Keys {
        val cloudBackupEnabled = booleanPreferencesKey("cloud_backup_enabled")
        val gcsBucket = stringPreferencesKey("gcs_bucket")
        val gcsPrefix = stringPreferencesKey("gcs_prefix")
        val legacyGcsSignedUrl = stringPreferencesKey("gcs_signed_url")
        val backupIncludeDatabase = booleanPreferencesKey("backup_include_database")
        val backupIncludeImportCopies = booleanPreferencesKey("backup_include_import_copies")
        val backupLastAttemptEpochSeconds = longPreferencesKey("backup_last_attempt_epoch_seconds")
        val backupLastSuccessEpochSeconds = longPreferencesKey("backup_last_success_epoch_seconds")
        val backupLastMessage = stringPreferencesKey("backup_last_message")
        val backupLastOk = booleanPreferencesKey("backup_last_ok")
        val statsGranularity = stringPreferencesKey("stats_granularity")
        val statsSelectedDeviceId = longPreferencesKey("stats_selected_device_id")
        val languageTag = stringPreferencesKey("language_tag")
        val hourAggregatesBackfilled = booleanPreferencesKey("hour_aggregates_backfilled")
        val hourAggregatesSchemaVersion = intPreferencesKey("hour_aggregates_schema_version")
        val livePollIntervalSeconds = longPreferencesKey("live_poll_interval_seconds")
        val widgetDeviceId = longPreferencesKey("widget_device_id")
        val inverterWarningAlertsEnabled = booleanPreferencesKey("inverter_warning_alerts_enabled")
        val eventAlertWatermarks = stringPreferencesKey("event_alert_watermarks")
    }
}
