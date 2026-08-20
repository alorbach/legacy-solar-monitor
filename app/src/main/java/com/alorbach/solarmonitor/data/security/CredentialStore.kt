package com.alorbach.solarmonitor.data.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID

/**
 * Stores secrets (SMA PIN, FTP/SFTP passwords) in EncryptedSharedPreferences.
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
        val id = existingId?.takeIf { it.isNotBlank() } ?: newId()
        // commit() so Room job rows that reference this id cannot outlive an unflushed apply().
        check(prefs.edit().putString(id, value).commit()) {
            "Failed to persist credential"
        }
        return id
    }

    /** Pre-allocate an id so Room can reference a secret before the value is written. */
    fun allocateSecretId(): String = newId()

    fun getSecret(id: String?): String? {
        if (id.isNullOrBlank()) return null
        return prefs.getString(id, null)
    }

    fun deleteSecret(id: String?) {
        if (id.isNullOrBlank()) return
        // commit() for durability, but never throw: device delete must still remove the Room row.
        prefs.edit().remove(id).commit()
    }

    fun putNamed(key: String, value: String) {
        prefs.edit().putString(namedKey(key), value).apply()
    }

    fun getNamed(key: String): String? = prefs.getString(namedKey(key), null)

    fun deleteNamed(key: String) {
        prefs.edit().remove(namedKey(key)).apply()
    }

    fun isCredentialId(id: String?): Boolean = !id.isNullOrBlank() && id.startsWith("cred_")

    /** Plain PIN, or the secret stored under a credential id. Legacy Room values were plaintext. */
    fun resolveSmaPin(passwordRef: String?): String? {
        if (passwordRef.isNullOrBlank()) return null
        return if (isCredentialId(passwordRef)) getSecret(passwordRef) else passwordRef
    }

    fun persistSmaPin(plainPin: String, existingRef: String?): String {
        val existingId = existingRef.takeIf { isCredentialId(it) }
        return putSecret(plainPin.trim(), existingId)
    }

    private fun newId(): String = "cred_${UUID.randomUUID()}"

    private fun namedKey(key: String): String = "named_$key"

    companion object {
        private const val FILE_NAME = "solar_monitor_secrets"
        const val KEY_GCS_SIGNED_URL = "gcs_signed_url"
        const val KEY_GCS_SIGNED_GET_URL = "gcs_signed_get_url"
    }
}
