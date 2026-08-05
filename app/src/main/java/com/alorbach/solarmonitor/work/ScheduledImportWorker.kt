package com.alorbach.solarmonitor.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.alorbach.solarmonitor.SolarMonitorApplication
import com.alorbach.solarmonitor.data.importing.ImportRequest

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
            "URL" -> ImportRequest.UrlRequest(deviceId, inputData.getString(KEY_URL) ?: return Result.failure(), sourceLabel)
            "FTP" -> ImportRequest.FtpRequest(
                deviceId = deviceId,
                host = inputData.getString(KEY_HOST) ?: return Result.failure(),
                username = inputData.getString(KEY_USERNAME) ?: return Result.failure(),
                password = inputData.getString(KEY_PASSWORD) ?: return Result.failure(),
                path = inputData.getString(KEY_PATH) ?: return Result.failure(),
                sourceLabel = sourceLabel,
            )
            "SFTP" -> ImportRequest.SftpRequest(
                deviceId = deviceId,
                host = inputData.getString(KEY_HOST) ?: return Result.failure(),
                username = inputData.getString(KEY_USERNAME) ?: return Result.failure(),
                password = inputData.getString(KEY_PASSWORD) ?: return Result.failure(),
                path = inputData.getString(KEY_PATH) ?: return Result.failure(),
                sourceLabel = sourceLabel,
            )
            else -> return Result.failure()
        }

        return container.importManager.run(request)
            .fold(
                onSuccess = { Result.success() },
                onFailure = { Result.retry() },
            )
    }

    companion object {
        const val KEY_SOURCE_TYPE = "source_type"
        const val KEY_SOURCE_LABEL = "source_label"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_URL = "url"
        const val KEY_HOST = "host"
        const val KEY_USERNAME = "username"
        const val KEY_PASSWORD = "password"
        const val KEY_PATH = "path"
    }
}
