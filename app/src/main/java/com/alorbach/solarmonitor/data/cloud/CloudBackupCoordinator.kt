package com.alorbach.solarmonitor.data.cloud

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.alorbach.solarmonitor.work.CloudBackupWorker
import java.util.concurrent.TimeUnit

class CloudBackupCoordinator(
    private val appContext: Context,
) {
    fun enqueue(trigger: BackupTrigger, delaySeconds: Long = 0L) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val requestBuilder = OneTimeWorkRequestBuilder<CloudBackupWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInputData(workDataOf(CloudBackupPolicy.KEY_TRIGGER to trigger.name))
        if (delaySeconds > 0L) {
            requestBuilder.setInitialDelay(delaySeconds, TimeUnit.SECONDS)
        }
        val request = requestBuilder.build()
        val workManager = WorkManager.getInstance(appContext)
        if (delaySeconds > 0L) {
            // Separate unique name so KEEP/REPLACE on the main chain cannot drop deferred Auto,
            // while REPLACE here coalesces multiple throttle follow-ups into one delayed run.
            workManager.enqueueUniqueWork(
                CloudBackupPolicy.UNIQUE_DEFERRED_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
            return
        }
        // Manual REPLACE forces a fresh run.
        // Immediate Auto APPEND_OR_REPLACE chains behind an in-flight upload.
        val policy = when (trigger) {
            BackupTrigger.Manual -> ExistingWorkPolicy.REPLACE
            BackupTrigger.Auto -> ExistingWorkPolicy.APPEND_OR_REPLACE
        }
        workManager.enqueueUniqueWork(
            CloudBackupPolicy.UNIQUE_WORK_NAME,
            policy,
            request,
        )
    }
}
