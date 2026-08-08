package com.alorbach.solarmonitor.data.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID

/**
 * Stores secrets (SMA PIN, FTP/SFTP passwords, GCS signed URLs) in EncryptedSharedPreferences.
 * Room / WorkManager only keep opaque credential IDs or never store the secret at all.
 */
class CredentialStore(context: Context) {
    private val appContext = context.applicationContext

    // Keystore access and key derivation are slow enough to matter during Application.onCreate,
    // so the encrypted file is opened on first secret access instead of at construction.
    private val prefs: SharedPreferences by lazy {
        EncryptedSharedPreferences.create(
            appContext,
            FILE_NAME,
            MasterKey.Builder(appContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun putSecret(value: String, existingId: String? = null): String {
        val id = existingId?.takeIf { it.isNotBlank() && prefs.contains(it) } ?: newId()
        prefs.edit().putString(id, value).apply()
        return id
    }

    fun getSecret(id: String?): String? {
        if (id.isNullOrBlank()) return null
        return prefs.getString(id, null)
    }

    fun deleteSecret(id: String?) {
        if (id.isNullOrBlank()) return
        prefs.edit().remove(id).apply()
    }

    fun putNamed(key: String, value: String) {
        prefs.edit().putString(namedKey(key), value).apply()
    }

    fun getNamed(key: String): String? = prefs.getString(namedKey(key), null)

    fun deleteNamed(key: String) {
        prefs.edit().remove(namedKey(key)).apply()
    }

    private fun newId(): String = "cred_${UUID.randomUUID()}"

    private fun namedKey(key: String): String = "named_$key"

    companion object {
        private const val FILE_NAME = "solar_monitor_secrets"
        const val KEY_GCS_SIGNED_URL = "gcs_signed_url"
    }
}
