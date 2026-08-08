package com.alorbach.solarmonitor.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.alorbach.solarmonitor.SolarMonitorApplication
import com.alorbach.solarmonitor.data.importing.ImportRequest
import java.util.concurrent.TimeUnit

class ScheduledImportWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val sourceType = inputData.getString(KEY_SOURCE_TYPE) ?: return Result.failure()
        val deviceId = inputData.getLong(KEY_DEVICE_ID, -1).takeIf { it > 0 } ?: return Result.failure()
        val sourceLabel = inputData.getString(KEY_SOURCE_LABEL) ?: "Scheduled import"
        val container = (applicationContext as SolarMonitorApplication).container

        val request = when (sourceType) {
            "URL" -> ImportRequest.UrlRequest(
                deviceId,
                inputData.getString(KEY_URL) ?: return Result.failure(),
                sourceLabel,
            )
            "FTP" -> ImportRequest.FtpRequest(
                deviceId = deviceId,
                host = inputData.getString(KEY_HOST) ?: return Result.failure(),
                username = inputData.getString(KEY_USERNAME) ?: return Result.failure(),
                passwordCredentialId = inputData.getString(KEY_PASSWORD_CREDENTIAL_ID)
                    ?: return Result.failure(),
                path = inputData.getString(KEY_PATH) ?: return Result.failure(),
                sourceLabel = sourceLabel,
            )
            "SFTP" -> ImportRequest.SftpRequest(
                deviceId = deviceId,
                host = inputData.getString(KEY_HOST) ?: return Result.failure(),
                username = inputData.getString(KEY_USERNAME) ?: return Result.failure(),
                passwordCredentialId = inputData.getString(KEY_PASSWORD_CREDENTIAL_ID)
                    ?: return Result.failure(),
                path = inputData.getString(KEY_PATH) ?: return Result.failure(),
                sourceLabel = sourceLabel,
            )
            else -> return Result.failure()
        }

        return container.importManager.run(request)
            .fold(
                onSuccess = { Result.success() },
                onFailure = { error ->
                    // Retry only transient failures
                    val message = error.message.orEmpty().lowercase()
                    if (message.contains("timeout") ||
                        message.contains("connection") ||
                        message.contains("unavailable") ||
                        message.contains("temporary")
                    ) {
                        Result.retry()
                    } else {
                        Result.failure()
                    }
                },
            )
    }

    companion object {
        const val UNIQUE_WORK_NAME = "scheduled_import"
        const val KEY_SOURCE_TYPE = "source_type"
        const val KEY_SOURCE_LABEL = "source_label"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_URL = "url"
        const val KEY_HOST = "host"
        const val KEY_USERNAME = "username"
        const val KEY_PASSWORD_CREDENTIAL_ID = "password_credential_id"
        const val KEY_PATH = "path"

        fun credentialTag(credentialId: String): String = "import_cred_$credentialId"

        fun enqueueUrlImport(
            context: Context,
            deviceId: Long,
            url: String,
            sourceLabel: String = "Scheduled URL import",
            intervalHours: Long = 6,
        ) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<ScheduledImportWorker>(intervalHours, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .setInputData(
                    workDataOf(
                        KEY_SOURCE_TYPE to "URL",
                        KEY_SOURCE_LABEL to sourceLabel,
                        KEY_DEVICE_ID to deviceId,
                        KEY_URL to url,
                    )
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
