package com.alorbach.solarmonitor

import com.alorbach.solarmonitor.data.cloud.BackupSkipReason
import com.alorbach.solarmonitor.data.cloud.CloudBackupPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudBackupPolicyTest {
    @Test
    fun buildUploadUrl_substitutesBucketPrefixAndFilename() {
        val url = CloudBackupPolicy.buildUploadUrl(
            template = "https://storage.example/{bucket}/{prefix}/{filename}?sig=abc",
            bucket = "my-bucket",
            prefix = "/solar-monitor/",
            filename = "solar-monitor.db",
        )
        assertEquals(
            "https://storage.example/my-bucket/solar-monitor/solar-monitor.db?sig=abc",
            url,
        )
    }

    @Test
    fun buildUploadUrl_keepsDistinctFilenames() {
        val template = "https://storage.example/{bucket}/{prefix}/{filename}"
        val db = CloudBackupPolicy.buildUploadUrl(template, "b", "p", "solar-monitor.db")
        val csv = CloudBackupPolicy.buildUploadUrl(template, "b", "p", "daydata.csv")
        assertEquals("https://storage.example/b/p/solar-monitor.db", db)
        assertEquals("https://storage.example/b/p/daydata.csv", csv)
    }

    @Test
    fun resolveSkipReason_whenDisabledOrBlankUrl() {
        assertEquals(
            BackupSkipReason.NOT_CONFIGURED,
            CloudBackupPolicy.resolveSkipReason(
                enabled = false,
                signedUrlBlank = true,
                includeDatabase = true,
                includeImportCopies = true,
            ),
        )
        assertEquals(
            BackupSkipReason.NOT_CONFIGURED,
            CloudBackupPolicy.resolveSkipReason(
                enabled = true,
                signedUrlBlank = true,
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
                signedUrlBlank = false,
                includeDatabase = false,
                includeImportCopies = false,
            ),
        )
        assertNull(
            CloudBackupPolicy.resolveSkipReason(
                enabled = true,
                signedUrlBlank = false,
                includeDatabase = true,
                includeImportCopies = false,
            ),
        )
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
    fun displaySignedUrlTemplate_usesDefaultWhenBlank() {
        assertEquals(
            "https://storage.googleapis.com/{bucket}/{prefix}/solar-monitor.db",
            CloudBackupPolicy.displaySignedUrlTemplate(""),
        )
        assertEquals(
            "https://storage.googleapis.com/my-bucket/solar-monitor/solar-monitor.db",
            CloudBackupPolicy.displaySignedUrlTemplate("", "my-bucket", "solar-monitor"),
        )
        assertEquals(
            "https://example/signed",
            CloudBackupPolicy.displaySignedUrlTemplate("https://example/signed"),
        )
    }

    @Test
    fun buildPathTemplate_usesConfiguredValues() {
        assertEquals(
            "https://storage.googleapis.com/my-bucket/backups/{filename}",
            CloudBackupPolicy.buildPathTemplate("my-bucket", "/backups/"),
        )
        assertEquals(
            CloudBackupPolicy.DEFAULT_SIGNED_URL_TEMPLATE,
            CloudBackupPolicy.buildPathTemplate("", ""),
        )
    }

    @Test
    fun withAutoPath_dropsSignatureQuery() {
        val updated = CloudBackupPolicy.withAutoPath(
            existingUrl = "https://storage.googleapis.com/{bucket}/{prefix}/{filename}?X-Goog-Signature=abc",
            bucket = "prod",
            prefix = "solar",
        )
        assertEquals(
            "https://storage.googleapis.com/prod/solar/solar-monitor.db",
            updated,
        )
    }

    @Test
    fun selectableBackupFilenames_limitsSignedPlaceholderToDatabase() {
        val template =
            "https://storage.googleapis.com/b/p/{filename}?X-Goog-Signature=abc"
        assertEquals(
            listOf("solar-monitor.db"),
            CloudBackupPolicy.selectableBackupFilenames(
                template,
                listOf("solar-monitor.db", "day.csv", "archive.zip"),
            ),
        )
    }

    @Test
    fun selectableBackupFilenames_matchesNestedDeviceImportPaths() {
        val template =
            "https://storage.googleapis.com/bucket/imports/device-1/day.csv?X-Goog-Signature=abc"
        assertEquals(
            listOf("device-1/day.csv"),
            CloudBackupPolicy.selectableBackupFilenames(
                template,
                listOf("solar-monitor.db", "device-1/day.csv", "device-2/day.csv", "day.csv"),
            ),
        )
    }

    @Test
    fun selectableBackupFilenames_fallsBackToUniqueBasenameForLegacySignedUrl() {
        val template =
            "https://storage.googleapis.com/bucket/imports/day.csv?X-Goog-Signature=abc"
        assertEquals(
            listOf("device-1/day.csv"),
            CloudBackupPolicy.selectableBackupFilenames(
                template,
                listOf("solar-monitor.db", "device-1/day.csv"),
            ),
        )
        assertEquals(
            emptyList<String>(),
            CloudBackupPolicy.selectableBackupFilenames(
                template,
                listOf("device-1/day.csv", "device-2/day.csv"),
            ),
        )
    }

    @Test
    fun selectableBackupFilenames_prefersNestedOverLegacyFlatCopy() {
        val template =
            "https://storage.googleapis.com/bucket/imports/day.csv?X-Goog-Signature=abc"
        assertEquals(
            listOf("device-1/day.csv"),
            CloudBackupPolicy.selectableBackupFilenames(
                template,
                listOf("day.csv", "device-1/day.csv"),
            ),
        )
    }

    @Test
    fun selectableBackupFilenames_doesNotCrossMatchNestedDeviceUrls() {
        val template =
            "https://storage.googleapis.com/bucket/imports/device-1/day.csv?X-Goog-Signature=abc"
        assertEquals(
            emptyList<String>(),
            CloudBackupPolicy.selectableBackupFilenames(
                template,
                listOf("device-2/day.csv"),
            ),
        )
    }

    @Test
    fun isUploadConfigured_rejectsBlankAndPlaceholderDefault() {
        assertFalse(CloudBackupPolicy.isUploadConfigured(""))
        assertFalse(CloudBackupPolicy.isUploadConfigured(CloudBackupPolicy.DEFAULT_SIGNED_URL_TEMPLATE))
        assertFalse(
            CloudBackupPolicy.isUploadConfigured(
                "https://storage.googleapis.com/my-bucket/solar-monitor/{filename}",
            ),
        )
        assertFalse(
            CloudBackupPolicy.isUploadConfigured(
                "http://storage.googleapis.com/my-bucket/solar-monitor.db?X-Goog-Signature=abc",
            ),
        )
        assertTrue(
            CloudBackupPolicy.isUploadConfigured(
                CloudBackupPolicy.buildDatabaseObjectUrl("my-bucket", "solar-monitor") +
                    "?X-Goog-Signature=abc",
            ),
        )
    }

    @Test
    fun isRestoreConfigured_requiresHttpsQueryAndDatabaseObject() {
        assertFalse(CloudBackupPolicy.isRestoreConfigured(""))
        assertFalse(
            CloudBackupPolicy.isRestoreConfigured(
                "https://storage.googleapis.com/my-bucket/solar-monitor/solar-monitor.db",
            ),
        )
        assertFalse(
            CloudBackupPolicy.isRestoreConfigured(
                "http://storage.googleapis.com/my-bucket/solar-monitor/solar-monitor.db?X-Goog-Signature=abc",
            ),
        )
        assertFalse(
            CloudBackupPolicy.isRestoreConfigured(
                "https://storage.googleapis.com/my-bucket/solar-monitor/day.csv?X-Goog-Signature=abc",
            ),
        )
        assertTrue(
            CloudBackupPolicy.isRestoreConfigured(
                CloudBackupPolicy.buildDatabaseObjectUrl("my-bucket", "solar-monitor") +
                    "?X-Goog-Signature=abc",
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
    fun isTransientUploadFailure_coversDnsAndConnectOutages() {
        assertTrue(
            CloudBackupPolicy.isTransientUploadFailure(
                "Unable to resolve host storage.googleapis.com: No address associated with hostname",
            ),
        )
        assertTrue(CloudBackupPolicy.isTransientUploadFailure("Failed to connect to storage.googleapis.com"))
        assertTrue(CloudBackupPolicy.isTransientUploadFailure("Network is unreachable"))
        assertTrue(CloudBackupPolicy.isTransientUploadFailure("gcs upload failed: 503"))
        assertFalse(CloudBackupPolicy.isTransientUploadFailure("gcs upload failed: 403"))
    }
}
