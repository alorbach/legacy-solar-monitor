package com.alorbach.solarmonitor.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.alorbach.solarmonitor.SolarMonitorApplication
import com.alorbach.solarmonitor.data.importing.ImportAlreadyRunningException
import com.alorbach.solarmonitor.data.importing.ImportRequest
import com.alorbach.solarmonitor.data.importing.replayConfig
import com.alorbach.solarmonitor.data.model.ImportJobEntity
import com.alorbach.solarmonitor.service.ImportNotifications
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ScheduledImportWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun getForegroundInfo(): ForegroundInfo =
        ImportNotifications.foregroundInfo(applicationContext, 0, 0)

    override suspend fun doWork(): Result {
        inFlight.incrementAndGet()
        try {
            return doScheduledImport()
        } finally {
            inFlight.decrementAndGet()
        }
    }

    private suspend fun doScheduledImport(): Result {
        val sourceType = inputData.getString(KEY_SOURCE_TYPE) ?: return Result.failure()
        val deviceId = inputData.getLong(KEY_DEVICE_ID, -1).takeIf { it > 0 } ?: return Result.failure()
        val sourceLabel = inputData.getString(KEY_SOURCE_LABEL) ?: "Scheduled import"
        val container = (applicationContext as SolarMonitorApplication).container

        setForeground(ImportNotifications.foregroundInfo(applicationContext, 0, 0))

        val request = when (sourceType) {
            "URL" -> {
                val credentialId = inputData.getString(KEY_PASSWORD_CREDENTIAL_ID)?.takeIf { it.isNotBlank() }
                val url = if (credentialId != null) {
                    container.credentialStore.getSecret(credentialId) ?: return Result.failure()
                } else {
                    inputData.getString(KEY_URL)?.takeIf { it.isNotBlank() } ?: return Result.failure()
                }
                ImportRequest.UrlRequest(deviceId, url, sourceLabel)
            }
            "FTP" -> ImportRequest.FtpRequest(
                deviceId = deviceId,
                host = inputData.getString(KEY_HOST) ?: return Result.failure(),
                username = inputData.getString(KEY_USERNAME) ?: return Result.failure(),
                passwordCredentialId = inputData.getString(KEY_PASSWORD_CREDENTIAL_ID)
                    ?: return Result.failure(),
                path = inputData.getString(KEY_PATH) ?: return Result.failure(),
                directory = inputData.getBoolean(KEY_DIRECTORY, false),
                sourceLabel = sourceLabel,
            )
            "SFTP" -> ImportRequest.SftpRequest(
                deviceId = deviceId,
                host = inputData.getString(KEY_HOST) ?: return Result.failure(),
                username = inputData.getString(KEY_USERNAME) ?: return Result.failure(),
                passwordCredentialId = inputData.getString(KEY_PASSWORD_CREDENTIAL_ID)
                    ?: return Result.failure(),
                path = inputData.getString(KEY_PATH) ?: return Result.failure(),
                directory = inputData.getBoolean(KEY_DIRECTORY, false),
                sourceLabel = sourceLabel,
            )
            else -> return Result.failure()
        }

        return container.importManager.run(request)
            .fold(
                onSuccess = { Result.success() },
                onFailure = { error ->
                    if (error.shouldRetryScheduledImport()) Result.retry() else Result.failure()
                },
            )
    }

    companion object {
        const val UNIQUE_WORK_NAME = "scheduled_import"
        private val inFlight = AtomicInteger(0)

        fun isInFlight(): Boolean = inFlight.get() > 0
        const val KEY_SOURCE_TYPE = "source_type"
        const val KEY_SOURCE_LABEL = "source_label"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_URL = "url"
        const val KEY_HOST = "host"
        const val KEY_USERNAME = "username"
        const val KEY_PASSWORD_CREDENTIAL_ID = "password_credential_id"
        const val KEY_PATH = "path"
        const val KEY_DIRECTORY = "directory"

        fun credentialTag(credentialId: String): String = "import_cred_$credentialId"

        fun deviceTag(deviceId: Long): String = "import_device_$deviceId"

        fun uniqueName(jobId: Long): String = "scheduled_import_$jobId"

        fun cancel(context: Context, jobId: Long) {
            WorkManager.getInstance(context).cancelUniqueWork(uniqueName(jobId))
        }

        fun cancelAll(context: Context, jobIds: Collection<Long>) {
            val workManager = WorkManager.getInstance(context)
            jobIds.forEach { workManager.cancelUniqueWork(uniqueName(it)) }
            workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
            workManager.cancelAllWorkByTag(UNIQUE_WORK_NAME)
        }

        fun enqueueJob(
            context: Context,
            job: ImportJobEntity,
            intervalHours: Long,
        ): Boolean {
            val config = job.replayConfig() ?: return false
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val data = when (config.kind) {
                "URL" -> {
                    val credentialId = job.passwordCredentialId?.takeIf { it.isNotBlank() }
                    val url = config.url?.takeIf { it.isNotBlank() }
                    if (credentialId == null && url == null) return false
                    workDataOf(
                        KEY_SOURCE_TYPE to "URL",
                        KEY_SOURCE_LABEL to config.sourceLabel,
                        KEY_DEVICE_ID to config.deviceId,
                        KEY_URL to (url ?: ""),
                        KEY_PASSWORD_CREDENTIAL_ID to (credentialId ?: ""),
                    )
                }
                "FTP", "SFTP" -> {
                    val passwordCredentialId = job.passwordCredentialId ?: return false
                    val host = config.host ?: return false
                    val path = config.path ?: return false
                    workDataOf(
                        KEY_SOURCE_TYPE to config.kind,
                        KEY_SOURCE_LABEL to config.sourceLabel,
                        KEY_DEVICE_ID to config.deviceId,
                        KEY_HOST to host,
                        KEY_USERNAME to (config.username ?: ""),
                        KEY_PASSWORD_CREDENTIAL_ID to passwordCredentialId,
                        KEY_PATH to path,
                        KEY_DIRECTORY to config.directory,
                    )
                }
                else -> return false
            }
            val request = PeriodicWorkRequestBuilder<ScheduledImportWorker>(
                intervalHours.coerceIn(1L, 168L),
                TimeUnit.HOURS,
            )
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .setInputData(data)
                .apply {
                    addTag(UNIQUE_WORK_NAME)
                    job.passwordCredentialId?.let { addTag(credentialTag(it)) }
                    addTag(deviceTag(config.deviceId))
                }
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                uniqueName(job.id),
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
            return true
        }
    }
}

internal fun Throwable.shouldRetryScheduledImport(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        if (current is ImportAlreadyRunningException) return true
        when (current) {
            is java.net.SocketTimeoutException,
            is java.net.ConnectException,
            is java.net.UnknownHostException,
            is java.net.NoRouteToHostException,
            is java.net.SocketException,
            is java.io.InterruptedIOException,
            -> return true
        }
        current = current.cause
    }
    return false
}
