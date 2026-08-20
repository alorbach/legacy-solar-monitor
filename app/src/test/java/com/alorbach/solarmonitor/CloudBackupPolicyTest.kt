package com.alorbach.solarmonitor

import com.alorbach.solarmonitor.data.cloud.BackupSkipReason
import com.alorbach.solarmonitor.data.cloud.CloudBackupPolicy
import com.alorbach.solarmonitor.data.cloud.DriveJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudBackupPolicyTest {
    @Test
    fun resolveSkipReason_whenDisabledOrSignedOut() {
        assertEquals(
            BackupSkipReason.NOT_CONFIGURED,
            CloudBackupPolicy.resolveSkipReason(
                enabled = false,
                signedIn = false,
                includeDatabase = true,
                includeImportCopies = true,
            ),
        )
        assertEquals(
            BackupSkipReason.NOT_CONFIGURED,
            CloudBackupPolicy.resolveSkipReason(
                enabled = true,
                signedIn = false,
                includeDatabase = true,
                includeImportCopies = true,
            ),
        )
        assertEquals(
            BackupSkipReason.NOT_CONFIGURED,
            CloudBackupPolicy.resolveSkipReason(
                enabled = false,
                signedIn = true,
                includeDatabase = true,
                includeImportCopies = true,
            ),
        )
    }

    @Test
    fun resolveSkipReason_whenNoContentSelected() {
        assertEquals(
            BackupSkipReason.NO_CONTENT,
            CloudBackupPolicy.resolveSkipReason(
                enabled = true,
                signedIn = true,
                includeDatabase = false,
                includeImportCopies = false,
            ),
        )
        assertNull(
            CloudBackupPolicy.resolveSkipReason(
                enabled = true,
                signedIn = true,
                includeDatabase = true,
                includeImportCopies = false,
            ),
        )
    }

    @Test
    fun isAccountConfigured_requiresNonBlankEmail() {
        assertFalse(CloudBackupPolicy.isAccountConfigured(""))
        assertFalse(CloudBackupPolicy.isAccountConfigured("   "))
        assertTrue(CloudBackupPolicy.isAccountConfigured("user@gmail.com"))
    }

    @Test
    fun driveFileName_replacesPathSeparators() {
        assertEquals("solar-monitor.db", CloudBackupPolicy.driveFileName("solar-monitor.db"))
        assertEquals("device-1%2Fday.csv", CloudBackupPolicy.driveFileName("device-1/day.csv"))
        assertEquals("device-1%2Fnested%2Fday.csv", CloudBackupPolicy.driveFileName("/device-1/nested/day.csv"))
        assertEquals("device-1%252Fday.csv", CloudBackupPolicy.driveFileName("device-1%2Fday.csv"))
    }

    @Test
    fun shouldThrottleAuto_withinWindow() {
        val lastSuccess = 1_000_000L
        assertTrue(
            CloudBackupPolicy.shouldThrottleAuto(
                nowEpochSeconds = lastSuccess + CloudBackupPolicy.AUTO_THROTTLE_SECONDS - 1,
                lastSuccessEpochSeconds = lastSuccess,
            ),
        )
        assertFalse(
            CloudBackupPolicy.shouldThrottleAuto(
                nowEpochSeconds = lastSuccess + CloudBackupPolicy.AUTO_THROTTLE_SECONDS,
                lastSuccessEpochSeconds = lastSuccess,
            ),
        )
        assertFalse(
            CloudBackupPolicy.shouldThrottleAuto(
                nowEpochSeconds = lastSuccess + 1,
                lastSuccessEpochSeconds = null,
            ),
        )
    }

    @Test
    fun isSqliteDatabaseHeader_acceptsSqliteMagic() {
        val header = "SQLite format 3\u0000".toByteArray(Charsets.ISO_8859_1)
        assertTrue(CloudBackupPolicy.isSqliteDatabaseHeader(header))
        assertTrue(CloudBackupPolicy.isSqliteDatabaseHeader(header + byteArrayOf(1, 2, 3)))
        assertFalse(CloudBackupPolicy.isSqliteDatabaseHeader("not-sqlite".toByteArray()))
        assertFalse(CloudBackupPolicy.isSqliteDatabaseHeader(ByteArray(8)))
    }

    @Test
    fun isTransientUploadFailure_coversDnsConnectAndDriveStatus() {
        assertTrue(
            CloudBackupPolicy.isTransientUploadFailure(
                "Unable to resolve host www.googleapis.com: No address associated with hostname",
            ),
        )
        assertTrue(CloudBackupPolicy.isTransientUploadFailure("Failed to connect to www.googleapis.com"))
        assertTrue(CloudBackupPolicy.isTransientUploadFailure("Network is unreachable"))
        assertTrue(CloudBackupPolicy.isTransientUploadFailure("Drive request failed: 503"))
        assertTrue(CloudBackupPolicy.isTransientUploadFailure("Drive request failed: 429"))
        assertFalse(CloudBackupPolicy.isTransientUploadFailure("Drive request failed: 403"))
        assertFalse(CloudBackupPolicy.isTransientUploadFailure("Drive request failed: 401"))
    }

    @Test
    fun driveJson_parsesFileListAndEmail() {
        val files = DriveJson.files(
            """{"files":[{"id":"abc","name":"solar-monitor.db"},{"id":"def","name":"device-1_day.csv"}]}""",
        )
        assertEquals(2, files.size)
        assertEquals("abc", files[0].id)
        assertEquals("solar-monitor.db", files[0].name)
        assertEquals("def", files[1].id)
        assertEquals("user@gmail.com", DriveJson.stringField("""{"user":{"emailAddress":"user@gmail.com"}}""", "emailAddress"))
        assertEquals("\"Legacy Solar Monitor\"", DriveJson.jsonString("Legacy Solar Monitor"))
        assertEquals("O\\'Brien", DriveJson.driveQueryLiteral("O'Brien"))
    }

    @Test
    fun driveFolder_prefersNewNameAndFallsBackToPrevious() {
        assertEquals("Legacy Solar Monitor", CloudBackupPolicy.DRIVE_FOLDER_NAME)
        assertEquals("SMA Solar Monitor", CloudBackupPolicy.DRIVE_FOLDER_NAME_PREVIOUS)
        assertEquals(listOf("SMA Solar Monitor"), CloudBackupPolicy.driveFolderFallbackNames())
    }

    @Test
    fun pickDriveFolder_usesFallbackWhenPreferredIsEmpty() {
        val preferred = CloudBackupPolicy.DriveFolderCandidate(
            id = "new",
            name = CloudBackupPolicy.DRIVE_FOLDER_NAME,
            hasDatabaseBackup = false,
        )
        val previous = CloudBackupPolicy.DriveFolderCandidate(
            id = "old",
            name = CloudBackupPolicy.DRIVE_FOLDER_NAME_PREVIOUS,
            hasDatabaseBackup = true,
        )
        val chosen = CloudBackupPolicy.pickDriveFolder(
            CloudBackupPolicy.DRIVE_FOLDER_NAME,
            listOf(preferred, previous),
        )
        assertEquals("old", chosen?.id)
        assertFalse(
            CloudBackupPolicy.shouldRenameDriveFolder(
                CloudBackupPolicy.DRIVE_FOLDER_NAME,
                chosen!!,
                listOf(preferred, previous),
            ),
        )
    }

    @Test
    fun pickDriveFolder_prefersCachedBackupOverEmptyPreferred() {
        val cachedEmpty = CloudBackupPolicy.DriveFolderCandidate(
            id = "cached",
            name = CloudBackupPolicy.DRIVE_FOLDER_NAME,
            hasDatabaseBackup = false,
        )
        val previous = CloudBackupPolicy.DriveFolderCandidate(
            id = "old",
            name = CloudBackupPolicy.DRIVE_FOLDER_NAME_PREVIOUS,
            hasDatabaseBackup = true,
        )
        assertEquals(
            "old",
            CloudBackupPolicy.pickDriveFolder(
                CloudBackupPolicy.DRIVE_FOLDER_NAME,
                listOf(cachedEmpty, previous),
            )?.id,
        )
    }

    @Test
    fun pickDriveFolder_keepsDuplicateNamedFolderThatHasBackup() {
        val emptyPreferred = CloudBackupPolicy.DriveFolderCandidate(
            id = "empty",
            name = CloudBackupPolicy.DRIVE_FOLDER_NAME,
            hasDatabaseBackup = false,
        )
        val preferredWithDb = CloudBackupPolicy.DriveFolderCandidate(
            id = "full",
            name = CloudBackupPolicy.DRIVE_FOLDER_NAME,
            hasDatabaseBackup = true,
        )
        assertEquals(
            "full",
            CloudBackupPolicy.pickDriveFolder(
                CloudBackupPolicy.DRIVE_FOLDER_NAME,
                listOf(emptyPreferred, preferredWithDb),
            )?.id,
        )
    }

    @Test
    fun pickDriveFolder_renamesPreviousWhenPreferredMissing() {
        val previous = CloudBackupPolicy.DriveFolderCandidate(
            id = "old",
            name = CloudBackupPolicy.DRIVE_FOLDER_NAME_PREVIOUS,
            hasDatabaseBackup = true,
        )
        val chosen = CloudBackupPolicy.pickDriveFolder(
            CloudBackupPolicy.DRIVE_FOLDER_NAME,
            listOf(previous),
        )
        assertEquals("old", chosen?.id)
        assertTrue(
            CloudBackupPolicy.shouldRenameDriveFolder(
                CloudBackupPolicy.DRIVE_FOLDER_NAME,
                chosen!!,
                listOf(previous),
            ),
        )
    }

    @Test
    fun driveJson_parsesUnquotedTrashedBoolean() {
        assertEquals(true, DriveJson.booleanField("""{"id":"abc","trashed":true}""", "trashed"))
        assertEquals(false, DriveJson.booleanField("""{"id":"abc","trashed":false}""", "trashed"))
    }

    @Test
    fun driveJson_files_keepsNamesContainingBrackets() {
        val files = DriveJson.files(
            """{"files":[{"id":"abc","name":"device]1.csv"},{"id":"def","name":"ok.csv"}]}""",
        )
        assertEquals(2, files.size)
        assertEquals("device]1.csv", files[0].name)
        assertEquals("def", files[1].id)
    }

    @Test
    fun shouldRefreshBackupSuccess_onlyOnFullSuccess() {
        assertFalse(CloudBackupPolicy.shouldRefreshBackupSuccess(skipped = true, success = true))
        assertFalse(CloudBackupPolicy.shouldRefreshBackupSuccess(skipped = false, success = false))
        assertTrue(CloudBackupPolicy.shouldRefreshBackupSuccess(skipped = false, success = true))
    }
}
