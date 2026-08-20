package com.alorbach.solarmonitor.data.cloud

enum class BackupTrigger {
    Manual,
    Auto,
}

data class BackupResult(
    val skipped: Boolean,
    val success: Boolean,
    val message: String,
    val uploadedCount: Int = 0,
    /** When set, the worker should schedule another Auto attempt after this many seconds. */
    val deferSeconds: Long? = null,
    /** True when failure is likely transient (network/5xx) and WorkManager should retry. */
    val retryable: Boolean = false,
)

data class RestoreResult(
    val success: Boolean,
    val message: String,
    val shouldRestart: Boolean = false,
)

enum class BackupSkipReason {
    NOT_CONFIGURED,
    NO_CONTENT,
}

fun BackupSkipReason.toUserMessage(context: android.content.Context): String =
    context.getString(
        when (this) {
            BackupSkipReason.NOT_CONFIGURED -> com.alorbach.solarmonitor.R.string.backup_skip_not_configured
            BackupSkipReason.NO_CONTENT -> com.alorbach.solarmonitor.R.string.backup_skip_no_content
        },
    )

object CloudBackupPolicy {
    const val UNIQUE_WORK_NAME = "cloud_backup"
    /** Coalesces throttled Auto follow-ups without cancelling an in-flight backup. */
    const val UNIQUE_DEFERRED_WORK_NAME = "cloud_backup_deferred"
    const val KEY_TRIGGER = "trigger"
    const val AUTO_THROTTLE_SECONDS = 15 * 60L
    const val DATABASE_BACKUP_FILENAME = "solar-monitor.db"
    const val DRIVE_FOLDER_NAME = "Legacy Solar Monitor"
    /** Must match Room `@Database(version)` in SolarMonitorDatabase. */
    const val ROOM_USER_VERSION = 5
    /** Oldest schema Room can migrate without a destructive wipe (`fallbackToDestructiveMigrationFrom(1, 2)`). */
    const val MIN_MIGRATABLE_ROOM_VERSION = 3
    private const val SQLITE_USER_VERSION_OFFSET = 60
    /** Pre-rebrand folder name; still resolved so existing Drive backups are not orphaned. */
    const val DRIVE_FOLDER_NAME_PREVIOUS = "SMA Solar Monitor"

    fun driveFolderFallbackNames(): List<String> = listOf(DRIVE_FOLDER_NAME_PREVIOUS)

    data class DriveFolderCandidate(
        val id: String,
        val name: String,
        val hasDatabaseBackup: Boolean = false,
    )

    /**
     * Prefer a folder that already holds solar-monitor.db so an empty rebranded
     * folder cannot steal backup/restore from the pre-rebrand directory.
     */
    fun pickDriveFolder(
        preferredName: String,
        candidates: List<DriveFolderCandidate>,
    ): DriveFolderCandidate? {
        if (candidates.isEmpty()) return null
        val withDb = candidates.filter { it.hasDatabaseBackup }
        return withDb.firstOrNull { it.name == preferredName }
            ?: withDb.firstOrNull()
            ?: candidates.firstOrNull { it.name == preferredName }
            ?: candidates.first()
    }

    fun shouldRenameDriveFolder(
        preferredName: String,
        chosen: DriveFolderCandidate,
        candidates: List<DriveFolderCandidate>,
    ): Boolean {
        if (chosen.name == preferredName) return false
        return candidates.none { it.id != chosen.id && it.name == preferredName }
    }

    fun isAccountConfigured(email: String): Boolean = email.trim().isNotBlank()

    /** Drive has a flat folder; percent-encode path separators so nested imports stay unique. */
    fun driveFileName(objectName: String): String =
        objectName.trim('/').replace("%", "%25").replace("/", "%2F")

    fun resolveSkipReason(
        enabled: Boolean,
        signedIn: Boolean,
        includeDatabase: Boolean,
        includeImportCopies: Boolean,
    ): BackupSkipReason? = when {
        !enabled || !signedIn -> BackupSkipReason.NOT_CONFIGURED
        !includeDatabase && !includeImportCopies -> BackupSkipReason.NO_CONTENT
        else -> null
    }

    fun shouldThrottleAuto(nowEpochSeconds: Long, lastSuccessEpochSeconds: Long?): Boolean {
        val last = lastSuccessEpochSeconds ?: return false
        return nowEpochSeconds - last < AUTO_THROTTLE_SECONDS
    }

    fun throttleRemainingSeconds(nowEpochSeconds: Long, lastSuccessEpochSeconds: Long?): Long {
        val last = lastSuccessEpochSeconds ?: return 0L
        val remaining = AUTO_THROTTLE_SECONDS - (nowEpochSeconds - last)
        return remaining.coerceAtLeast(0L)
    }

    fun isSqliteDatabaseHeader(bytes: ByteArray): Boolean {
        if (bytes.size < SQLITE_HEADER.size) return false
        return bytes.sliceArray(SQLITE_HEADER.indices).contentEquals(SQLITE_HEADER)
    }

    /** SQLite `PRAGMA user_version` at header offset 60 (big-endian). Room stores schema version there. */
    fun sqliteUserVersion(header: ByteArray): Int? {
        if (!isSqliteDatabaseHeader(header)) return null
        if (header.size < SQLITE_USER_VERSION_OFFSET + 4) return null
        var version = 0
        for (index in 0 until 4) {
            version = (version shl 8) or (header[SQLITE_USER_VERSION_OFFSET + index].toInt() and 0xFF)
        }
        return version
    }

    fun isCompatibleRoomBackup(
        header: ByteArray,
        expectedVersion: Int = ROOM_USER_VERSION,
        minMigratableVersion: Int = MIN_MIGRATABLE_ROOM_VERSION,
    ): Boolean {
        val version = sqliteUserVersion(header) ?: return false
        return version in minMigratableVersion..expectedVersion
    }

    fun shouldRefreshBackupSuccess(skipped: Boolean, success: Boolean): Boolean =
        !skipped && success

    fun isTransientUploadFailure(message: String?): Boolean {
        val text = message.orEmpty().lowercase()
        return text.contains("timeout") ||
            text.contains("timed out") ||
            text.contains("connection") ||
            text.contains("connect") ||
            text.contains("unreachable") ||
            text.contains("unable to resolve host") ||
            text.contains("unknownhost") ||
            text.contains("unavailable") ||
            text.contains("temporary") ||
            text.contains("drive request failed: 5") ||
            text.contains("drive request failed: 429") ||
            text.contains("gcs upload failed: 5") ||
            text.contains("gcs upload failed: 429")
    }

    private val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.ISO_8859_1)
}
