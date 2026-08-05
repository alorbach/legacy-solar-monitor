package com.alorbach.solarmonitor.data.cloud

import android.content.Context
import com.alorbach.solarmonitor.data.settings.AppSettingsStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import kotlinx.coroutines.flow.first

class GoogleCloudStorageBackupRepository(
    private val context: Context,
    private val settingsStore: AppSettingsStore,
    private val client: OkHttpClient = OkHttpClient(),
) {
    suspend fun uploadIfConfigured(file: File): Result<Unit> = runCatching {
        val settings = settingsStore.settings.first()
        if (!settings.cloudBackupEnabled || settings.gcsSignedUrl.isBlank()) return@runCatching

        val url = settings.gcsSignedUrl
            .replace("{bucket}", settings.gcsBucket)
            .replace("{prefix}", settings.gcsPrefix.trim('/'))
            .replace("{filename}", file.name)

        val body = file.readBytes().toRequestBody("application/octet-stream".toMediaType())
        val request = Request.Builder()
            .url(url)
            .put(body)
            .build()

        client.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "GCS upload failed: ${response.code}" }
        }
    }
}
