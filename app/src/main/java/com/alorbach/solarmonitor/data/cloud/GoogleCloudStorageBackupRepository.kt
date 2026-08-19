package com.alorbach.solarmonitor.data.cloud

import android.content.Context
import android.os.Build
import com.alorbach.solarmonitor.R
import com.alorbach.solarmonitor.data.importing.SharedHttpClients
import com.alorbach.solarmonitor.data.local.SolarMonitorDatabase
import com.alorbach.solarmonitor.data.settings.AppSettings
import com.alorbach.solarmonitor.data.settings.AppSettingsStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody

class GoogleCloudStorageBackupRepository(
    private val context: Context,
    private val settingsStore: AppSettingsStore,
    private val database: SolarMonitorDatabase,
    private val client: OkHttpClient = SharedHttpClients.okHttp,
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val mutex = Mutex()

    suspend fun runBackup(trigger: BackupTrigger): BackupResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            val now = clock()
            val settings = settingsStore.settings.first()
            val skip = CloudBackupPolicy.resolveSkipReason(
                enabled = settings.cloudBackupEnabled,
                signedUrlBlank = settings.gcsSignedUrl.isBlank(),
                includeDatabase = settings.backupIncludeDatabase,
                includeImportCopies = settings.backupIncludeImportCopies,
            )
            if (skip != null) {
                return@withContext persistResult(
                    settings = settings,
                    now = now,
                    result = BackupResult(
                        skipped = true,
                        success = true,
                        message = skip.toUserMessage(context),
                    ),
                )
            }
            if (trigger == BackupTrigger.Auto &&
                CloudBackupPolicy.shouldThrottleAuto(now, settings.backupLastSuccessEpochSeconds)
            ) {
                val defer = CloudBackupPolicy.throttleRemainingSeconds(
                    now,
                    settings.backupLastSuccessEpochSeconds,
                ).coerceAtLeast(1L)
                return@withContext persistResult(
                    settings = settings,
                    now = now,
                    result = BackupResult(
                        skipped = true,
                        success = true,
                        message = context.getString(R.string.backup_deferred_throttled, defer),
                        deferSeconds = defer,
                    ),
                    updateAttempt = false,
                )
            }

            val importsRoot = context.getDir("imports", Context.MODE_PRIVATE)
            val staged = mutableListOf<File>()
            try {
                if (settings.backupIncludeDatabase) {
                    staged += createDatabaseSnapshot()
                }
                if (settings.backupIncludeImportCopies) {
                    staged += listImportCopies()
                }
                val stagedNames = staged.map { backupObjectName(importsRoot, it) }
                val allowedNames = CloudBackupPolicy.selectableBackupFilenames(
                    settings.gcsSignedUrl,
                    stagedNames,
                ).toSet()
                val uploadable = staged.filter { backupObjectName(importsRoot, it) in allowedNames }
                if (uploadable.isEmpty()) {
                    return@withContext persistResult(
                        settings = settings,
                        now = now,
                        result = BackupResult(
                            skipped = true,
                            success = true,
                            message = context.getString(R.string.backup_no_matching_files),
                        ),
                    )
                }

                var uploaded = 0
                val errors = mutableListOf<String>()
                for (file in uploadable) {
                    val objectName = backupObjectName(importsRoot, file)
                    runCatching { uploadFile(settings, file, objectName) }
                        .onSuccess { uploaded++ }
                        .onFailure { errors += "$objectName: ${it.message ?: context.getString(R.string.backup_upload_failed)}" }
                }
                val ok = errors.isEmpty()
                val message = if (ok) {
                    context.getString(R.string.backup_uploaded_files, uploaded)
                } else {
                    context.getString(
                        R.string.backup_uploaded_partial,
                        uploaded,
                        uploadable.size,
                        errors.joinToString("; "),
                    )
                }
                val retryable = !ok && errors.any { CloudBackupPolicy.isTransientUploadFailure(it) }
                persistResult(
                    settings = settings,
                    now = now,
                    result = BackupResult(
                        skipped = false,
                        success = ok,
                        message = message,
                        uploadedCount = uploaded,
                        retryable = retryable,
                    ),
                )
            } catch (t: Throwable) {
                persistResult(
                    settings = settings,
                    now = now,
                    result = BackupResult(
                        skipped = false,
                        success = false,
                        message = t.message ?: context.getString(R.string.backup_failed),
                        retryable = CloudBackupPolicy.isTransientUploadFailure(t.message),
                    ),
                )
            } finally {
                staged.forEach { file ->
                    if (file.parentFile?.name == STAGING_DIR) {
                        runCatching { file.delete() }
                    }
                }
            }
        }
    }

    /** Kept for callers that already have a concrete file; prefer [runBackup]. */
    suspend fun uploadIfConfigured(file: File): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val settings = settingsStore.settings.first()
            if (!settings.cloudBackupEnabled || settings.gcsSignedUrl.isBlank()) return@runCatching
            val allowed = CloudBackupPolicy.selectableBackupFilenames(
                settings.gcsSignedUrl,
                listOf(file.name),
            )
            require(allowed.isNotEmpty()) {
                "Signed URL does not cover ${file.name}"
            }
            uploadFile(settings, file, file.name)
        }
    }

    private fun uploadFile(settings: AppSettings, file: File, objectName: String) {
        val url = CloudBackupPolicy.buildUploadUrl(
            template = settings.gcsSignedUrl,
            bucket = settings.gcsBucket,
            prefix = settings.gcsPrefix,
            filename = objectName,
        )
        require(url.startsWith("https://", ignoreCase = true)) {
            "Cloud backup URL must use HTTPS"
        }
        val body = file.asRequestBody(OCTET_STREAM)
        val request = Request.Builder()
            .url(url)
            .put(body)
            .build()
        client.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "GCS upload failed: ${response.code}" }
        }
    }

    private fun createDatabaseSnapshot(): File {
        val staging = File(context.cacheDir, STAGING_DIR).also { it.mkdirs() }
        val target = File(staging, CloudBackupPolicy.DATABASE_BACKUP_FILENAME)
        if (target.exists()) {
            check(target.delete()) { "Unable to clear previous DB snapshot" }
        }
        val db = database.openHelper.writableDatabase
        if (Build.VERSION.SDK_INT >= 30) {
            val escaped = target.absolutePath.replace("'", "''")
            db.execSQL("VACUUM INTO '$escaped'")
            require(target.exists() && target.length() > 0L) { "VACUUM INTO produced empty snapshot" }
            return target
        }
        val source = context.getDatabasePath(DATABASE_NAME)
        require(source.exists()) { "Database file missing: ${source.absolutePath}" }
        val sourceWal = File(source.path + "-wal")
        val sourceShm = File(source.path + "-shm")
        val targetWal = File(target.path + "-wal")
        val targetShm = File(target.path + "-shm")
        // Hold an exclusive write lock while copying main+WAL+SHM so no commit can land mid-copy.
        // Checkpoint must not run inside this transaction (SQLite rejects it).
        db.beginTransaction()
        try {
            source.copyTo(target, overwrite = true)
            if (sourceWal.exists()) {
                sourceWal.copyTo(targetWal, overwrite = true)
            } else {
                targetWal.delete()
            }
            if (sourceShm.exists()) {
                sourceShm.copyTo(targetShm, overwrite = true)
            } else {
                targetShm.delete()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        // Merge WAL into the offline snapshot copy, then upload a single .db file.
        val snapshot = android.database.sqlite.SQLiteDatabase.openDatabase(
            target.absolutePath,
            null,
            android.database.sqlite.SQLiteDatabase.OPEN_READWRITE,
        )
        try {
            snapshot.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { cursor ->
                require(cursor.moveToFirst()) { "snapshot wal_checkpoint returned no rows" }
            }
        } finally {
            snapshot.close()
        }
        targetWal.delete()
        targetShm.delete()
        require(target.exists() && target.length() > 0L) { "DB snapshot copy failed" }
        return target
    }

    private fun listImportCopies(): List<File> {
        val dir = context.getDir("imports", Context.MODE_PRIVATE)
        return dir.walkTopDown()
            .filter { file ->
                file.isFile &&
                    file.length() > 0L &&
                    !file.name.equals(CloudBackupPolicy.DATABASE_BACKUP_FILENAME, ignoreCase = true)
            }
            .sortedBy { it.absolutePath }
            .toList()
    }

    /** Prefer device-relative paths so same basenames from different devices stay unique in GCS. */
    private fun backupObjectName(importsRoot: File, file: File): String {
        if (file.name.equals(CloudBackupPolicy.DATABASE_BACKUP_FILENAME, ignoreCase = true)) {
            return file.name
        }
        return runCatching { file.relativeTo(importsRoot).invariantSeparatorsPath }
            .getOrDefault(file.name)
    }

    private suspend fun persistResult(
        settings: AppSettings,
        now: Long,
        result: BackupResult,
        updateAttempt: Boolean = true,
    ): BackupResult {
        // Partial uploads still advance the success timestamp so Auto throttle prevents spam.
        val refreshSuccess = !result.skipped && (result.success || result.uploadedCount > 0)
        settingsStore.update {
            it.copy(
                backupLastAttemptEpochSeconds = if (updateAttempt) {
                    now
                } else {
                    settings.backupLastAttemptEpochSeconds
                },
                backupLastSuccessEpochSeconds = if (refreshSuccess) {
                    now
                } else {
                    settings.backupLastSuccessEpochSeconds
                },
                backupLastMessage = result.message,
                backupLastOk = when {
                    result.skipped -> settings.backupLastOk
                    else -> result.success
                },
            )
        }
        return result
    }

    companion object {
        private const val DATABASE_NAME = "solar-monitor.db"
        private const val STAGING_DIR = "backup-upload"
        private val OCTET_STREAM = "application/octet-stream".toMediaType()
    }
}
