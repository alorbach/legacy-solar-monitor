package com.alorbach.solarmonitor.data.cloud

import android.content.Context
import android.os.Build
import com.alorbach.solarmonitor.R
import com.alorbach.solarmonitor.data.local.SolarMonitorDatabase
import com.alorbach.solarmonitor.data.settings.AppSettings
import com.alorbach.solarmonitor.data.settings.AppSettingsStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class GoogleDriveBackupRepository(
    private val context: Context,
    private val settingsStore: AppSettingsStore,
    private val database: SolarMonitorDatabase,
    private val auth: GoogleDriveAuth,
    private val remote: DriveRemote = GoogleDriveRemote(
        tokenProvider = {
            val email = settingsStore.settings.first().googleAccountEmail
            auth.accessToken(email)
        },
    ),
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val mutex = Mutex()

    suspend fun runBackup(trigger: BackupTrigger): BackupResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            val now = clock()
            val settings = settingsStore.settings.first()
            val skip = CloudBackupPolicy.resolveSkipReason(
                enabled = settings.cloudBackupEnabled,
                signedIn = CloudBackupPolicy.isAccountConfigured(settings.googleAccountEmail),
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
                if (staged.isEmpty()) {
                    return@withContext persistResult(
                        settings = settings,
                        now = now,
                        result = BackupResult(
                            skipped = true,
                            success = true,
                            message = context.getString(R.string.backup_skip_no_content),
                        ),
                    )
                }

                val folderId = resolveFolderId(settings)
                var uploaded = 0
                val errors = mutableListOf<String>()
                for (file in staged) {
                    val objectName = backupObjectName(importsRoot, file)
                    val driveName = CloudBackupPolicy.driveFileName(objectName)
                    runCatching { remote.upsertFile(folderId, driveName, file) }
                        .onSuccess { uploaded++ }
                        .onFailure { errors += "$driveName: ${it.message ?: context.getString(R.string.backup_upload_failed)}" }
                }
                val ok = errors.isEmpty()
                val message = if (ok) {
                    context.getString(R.string.backup_uploaded_files, uploaded)
                } else {
                    context.getString(
                        R.string.backup_uploaded_partial,
                        uploaded,
                        staged.size,
                        errors.joinToString("; "),
                    )
                }
                val retryable = !ok && errors.any { CloudBackupPolicy.isTransientUploadFailure(it) }
                persistResult(
                    settings = settings,
                    now = now,
                    folderId = folderId,
                    result = BackupResult(
                        skipped = false,
                        success = ok,
                        message = message,
                        uploadedCount = uploaded,
                        retryable = retryable,
                    ),
                )
            } catch (error: GoogleDriveNeedsUserException) {
                persistResult(
                    settings = settings,
                    now = now,
                    result = BackupResult(
                        skipped = false,
                        success = false,
                        message = error.message ?: context.getString(R.string.backup_needs_reauth),
                        retryable = false,
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

    suspend fun runRestore(stopLiveMonitor: suspend () -> Unit): RestoreResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            val settings = settingsStore.settings.first()
            if (!CloudBackupPolicy.isAccountConfigured(settings.googleAccountEmail)) {
                return@withContext RestoreResult(
                    success = false,
                    message = context.getString(R.string.restore_not_configured),
                )
            }
            val downloadDir = File(context.cacheDir, RESTORE_DOWNLOAD_DIR).also { it.mkdirs() }
            val download = File(downloadDir, CloudBackupPolicy.DATABASE_BACKUP_FILENAME)
            if (download.exists()) {
                check(download.delete()) { "Unable to clear previous restore download" }
            }
            val folderId = runCatching { resolveFolderId(settings) }
                .getOrElse { error ->
                    return@withContext RestoreResult(
                        success = false,
                        message = error.message ?: context.getString(R.string.restore_failed),
                    )
                }
            runCatching {
                remote.downloadFile(folderId, CloudBackupPolicy.DATABASE_BACKUP_FILENAME, download)
            }.onFailure { error ->
                val message = when {
                    error is GoogleDriveNeedsUserException ->
                        error.message ?: context.getString(R.string.backup_needs_reauth)
                    error.message.orEmpty().contains("not found", ignoreCase = true) ->
                        context.getString(R.string.restore_file_missing)
                    else -> error.message ?: context.getString(R.string.restore_failed)
                }
                return@withContext RestoreResult(success = false, message = message)
            }
            val header = ByteArray(16)
            download.inputStream().use { stream ->
                var offset = 0
                while (offset < header.size) {
                    val read = stream.read(header, offset, header.size - offset)
                    if (read <= 0) break
                    offset += read
                }
            }
            if (!CloudBackupPolicy.isSqliteDatabaseHeader(header)) {
                download.delete()
                return@withContext RestoreResult(
                    success = false,
                    message = context.getString(R.string.restore_invalid_file),
                )
            }

            stopLiveMonitor()
            val dbFile = context.getDatabasePath(DATABASE_NAME)
            val previousDir = File(context.cacheDir, RESTORE_PREVIOUS_DIR).also { it.mkdirs() }
            val previous = File(previousDir, "${clock()}-$DATABASE_NAME")
            runCatching { database.close() }
            try {
                if (dbFile.exists()) {
                    dbFile.copyTo(previous, overwrite = true)
                }
                previousDir.listFiles()?.forEach { file ->
                    if (file.absolutePath != previous.absolutePath) file.delete()
                }
                File(dbFile.path + "-wal").delete()
                File(dbFile.path + "-shm").delete()
                download.copyTo(dbFile, overwrite = true)
                File(dbFile.path + "-wal").delete()
                File(dbFile.path + "-shm").delete()
            } catch (error: Throwable) {
                if (previous.exists()) {
                    runCatching { previous.copyTo(dbFile, overwrite = true) }
                }
                return@withContext RestoreResult(
                    success = false,
                    message = error.message ?: context.getString(R.string.restore_failed),
                    shouldRestart = true,
                )
            }
            val message = context.getString(R.string.restore_succeeded)
            runCatching {
                settingsStore.update {
                    it.copy(
                        driveFolderId = folderId,
                        backupLastAttemptEpochSeconds = clock(),
                        backupLastSuccessEpochSeconds = clock(),
                        backupLastMessage = message,
                        backupLastOk = true,
                    )
                }
            }
            RestoreResult(success = true, message = message, shouldRestart = true)
        }
    }

    private suspend fun resolveFolderId(settings: AppSettings): String {
        val folderId = remote.findOrCreateFolder(
            CloudBackupPolicy.DRIVE_FOLDER_NAME,
            settings.driveFolderId,
        )
        if (folderId != settings.driveFolderId) {
            settingsStore.update { it.copy(driveFolderId = folderId) }
        }
        return folderId
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
        folderId: String? = null,
    ): BackupResult {
        val refreshSuccess = CloudBackupPolicy.shouldRefreshBackupSuccess(result.skipped, result.success)
        settingsStore.update {
            it.copy(
                driveFolderId = folderId ?: settings.driveFolderId,
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
        private const val RESTORE_DOWNLOAD_DIR = "restore-download"
        private const val RESTORE_PREVIOUS_DIR = "restore-previous"
    }
}
