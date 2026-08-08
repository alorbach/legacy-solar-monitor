package com.alorbach.solarmonitor.data.importing

import android.content.Context
import com.alorbach.solarmonitor.data.cloud.BackupTrigger
import com.alorbach.solarmonitor.data.cloud.CloudBackupCoordinator
import com.alorbach.solarmonitor.data.model.ImportJobEntity
import com.alorbach.solarmonitor.data.model.ImportJobStatus
import com.alorbach.solarmonitor.data.repository.SolarRepository
import com.alorbach.solarmonitor.data.security.CredentialStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ImportManager(
    private val appContext: Context,
    private val repository: SolarRepository,
    private val importers: LegacySbfspotImporters,
    private val credentialStore: CredentialStore,
    private val cloudBackupCoordinator: CloudBackupCoordinator,
) {
    suspend fun run(request: ImportRequest): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val jobId = repository.recordImportJob(
                ImportJobEntity(
                    deviceId = request.deviceId,
                    sourceLabel = request.sourceLabel,
                    sourceType = request.sourceType,
                    status = ImportJobStatus.RUNNING,
                    createdAtEpochSeconds = System.currentTimeMillis() / 1000,
                )
            )
            try {
                val deviceId = requireNotNull(request.deviceId) { "Device must be selected for import" }
                var fileCount = 0
                var totalBytes = 0L
                var lastPreserved: String? = null
                val combinedSpot = mutableListOf<com.alorbach.solarmonitor.data.model.SpotSampleEntity>()
                val combinedDays = mutableListOf<com.alorbach.solarmonitor.data.model.DayAggregateEntity>()
                val combinedMonths = mutableListOf<com.alorbach.solarmonitor.data.model.MonthAggregateEntity>()
                val combinedEvents = mutableListOf<com.alorbach.solarmonitor.data.model.DeviceEventEntity>()
                val stagedCopies = mutableListOf<Pair<String, java.io.File>>()

                try {
                    forEachDownload(request) { name, bytes ->
                        totalBytes += bytes.size
                        require(totalBytes <= RemoteBrowseHelpers.MAX_FOLDER_IMPORT_TOTAL_BYTES) {
                            "Folder import exceeds ${RemoteBrowseHelpers.MAX_FOLDER_IMPORT_TOTAL_BYTES / (1024 * 1024)} MiB total"
                        }
                        fileCount++
                        val parsed = importers.parseFile(deviceId, name, bytes)
                        combinedSpot += parsed.spotSamples
                        combinedDays += parsed.dayAggregates
                        combinedMonths += parsed.monthAggregates
                        combinedEvents += parsed.events
                        val staged = java.io.File.createTempFile(
                            "import-",
                            ".bin",
                            appContext.cacheDir,
                        )
                        staged.writeBytes(bytes)
                        stagedCopies += parsed.preservedName to staged
                    }
                    require(fileCount > 0) { "No importable files found" }

                    repository.importBundle(
                        spotSamples = combinedSpot,
                        dayAggregates = combinedDays,
                        monthAggregates = combinedMonths,
                        events = combinedEvents,
                    )
                    for ((preservedName, staged) in stagedCopies) {
                        lastPreserved = repository.storeImportedCopy(preservedName, staged.readBytes())
                    }
                } finally {
                    stagedCopies.forEach { (_, file) -> runCatching { file.delete() } }
                }

                repository.completeImportJob(
                    jobId = jobId,
                    success = true,
                    message = "Imported $fileCount file(s): " +
                        "${combinedSpot.size} spot samples, ${combinedDays.size} days, " +
                        "${combinedMonths.size} months, ${combinedEvents.size} events",
                    copyPath = lastPreserved,
                )
                cloudBackupCoordinator.enqueue(BackupTrigger.Auto)
            } catch (t: Throwable) {
                repository.completeImportJob(jobId, false, t.message, null)
                throw t
            }
        }
    }

    private suspend fun forEachDownload(
        request: ImportRequest,
        handle: suspend (name: String, bytes: ByteArray) -> Unit,
    ) {
        suspend fun accept(name: String, bytes: ByteArray) {
            require(bytes.size <= RemoteBrowseHelpers.MAX_IMPORT_FILE_BYTES) {
                "Import file $name exceeds ${RemoteBrowseHelpers.MAX_IMPORT_FILE_BYTES / (1024 * 1024)} MiB limit"
            }
            handle(name, bytes)
        }
        when (request) {
            is ImportRequest.FileRequest -> {
                val (name, bytes) = importers.readUri(request.uri)
                accept(name, bytes)
            }
            is ImportRequest.UrlRequest -> {
                val (name, bytes) = importers.downloadUrl(request.url)
                accept(name, bytes)
            }
            is ImportRequest.FtpRequest -> {
                val password = resolvePassword(request.password, request.passwordCredentialId)
                if (request.directory) {
                    val files = importers.listCsvRecursiveFtp(
                        host = request.host,
                        port = request.port,
                        username = request.username,
                        password = password,
                        path = request.path,
                    )
                    require(files.isNotEmpty()) { "No CSV files found under ${request.path}" }
                    require(files.size <= RemoteBrowseHelpers.MAX_FOLDER_IMPORT_FILES) {
                        "Folder import exceeds ${RemoteBrowseHelpers.MAX_FOLDER_IMPORT_FILES} CSV files " +
                            "(found ${files.size})"
                    }
                    for (entry in files) {
                        val (name, bytes) = importers.downloadFtp(
                            host = request.host,
                            port = request.port,
                            username = request.username,
                            password = password,
                            path = entry.path,
                        )
                        accept(name, bytes)
                    }
                } else {
                    val (name, bytes) = importers.downloadFtp(
                        host = request.host,
                        port = request.port,
                        username = request.username,
                        password = password,
                        path = request.path,
                    )
                    accept(name, bytes)
                }
            }
            is ImportRequest.SftpRequest -> {
                val password = resolvePassword(request.password, request.passwordCredentialId)
                if (request.directory) {
                    val files = importers.listCsvRecursiveSftp(
                        host = request.host,
                        port = request.port,
                        username = request.username,
                        password = password,
                        path = request.path,
                    )
                    require(files.isNotEmpty()) { "No CSV files found under ${request.path}" }
                    require(files.size <= RemoteBrowseHelpers.MAX_FOLDER_IMPORT_FILES) {
                        "Folder import exceeds ${RemoteBrowseHelpers.MAX_FOLDER_IMPORT_FILES} CSV files " +
                            "(found ${files.size})"
                    }
                    for (entry in files) {
                        val (name, bytes) = importers.downloadSftp(
                            host = request.host,
                            port = request.port,
                            username = request.username,
                            password = password,
                            path = entry.path,
                        )
                        accept(name, bytes)
                    }
                } else {
                    val (name, bytes) = importers.downloadSftp(
                        host = request.host,
                        port = request.port,
                        username = request.username,
                        password = password,
                        path = request.path,
                    )
                    accept(name, bytes)
                }
            }
        }
    }

    private fun resolvePassword(inlinePassword: String?, credentialId: String?): String {
        // Empty string is intentional (e.g. anonymous FTP); only null falls back to credentials.
        if (inlinePassword != null) return inlinePassword
        return credentialStore.getSecret(credentialId)
            ?: error("Missing import password credential")
    }
}
