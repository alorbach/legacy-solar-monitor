package com.alorbach.solarmonitor.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.alorbach.solarmonitor.SolarMonitorApplication
import com.alorbach.solarmonitor.data.cloud.BackupTrigger
import com.alorbach.solarmonitor.data.cloud.CloudBackupPolicy

class CloudBackupWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val triggerName = inputData.getString(CloudBackupPolicy.KEY_TRIGGER) ?: BackupTrigger.Auto.name
        val trigger = runCatching { BackupTrigger.valueOf(triggerName) }.getOrDefault(BackupTrigger.Auto)
        val container = (applicationContext as SolarMonitorApplication).container
        val result = container.cloudBackupRepository.runBackup(trigger)
        val defer = result.deferSeconds
        if (defer != null && defer > 0L && trigger == BackupTrigger.Auto) {
            container.cloudBackupCoordinator.enqueue(BackupTrigger.Auto, delaySeconds = defer)
        }
        return when {
            result.success || result.skipped -> Result.success()
            result.retryable -> Result.retry()
            else -> Result.failure()
        }
    }
}
