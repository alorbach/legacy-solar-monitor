package com.alorbach.solarmonitor.data.importing

import android.content.Context
import com.alorbach.solarmonitor.data.cloud.BackupTrigger
import com.alorbach.solarmonitor.data.cloud.CloudBackupCoordinator
import com.alorbach.solarmonitor.data.model.ImportJobEntity
import com.alorbach.solarmonitor.data.model.ImportJobStatus
import com.alorbach.solarmonitor.data.repository.SolarRepository
import com.alorbach.solarmonitor.data.security.CredentialStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

class ImportManager(
    private val appContext: Context,
    private val repository: SolarRepository,
    private val importers: LegacySbfspotImporters,
    private val credentialStore: CredentialStore,
    private val cloudBackupCoordinator: CloudBackupCoordinator,
) {
    private data class ImportOutcome(
        val message: String,
        val copyPath: String? = null,
    )

    suspend fun run(
        request: ImportRequest,
        onProgress: ((current: Int, total: Int) -> Unit)? = null,
    ): Result<Unit> = withContext(Dispatchers.IO) {
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
                val outcome = when {
                    request is ImportRequest.FtpRequest && request.directory ->
                        importFolderSession(
                            deviceId = deviceId,
                            clearBeforeImport = request.clearBeforeImport,
                            onProgress = onProgress,
                        ) { handle, progress ->
                            val password = resolvePassword(request.password, request.passwordCredentialId)
                            importers.forEachCsvInFtpFolder(
                                host = request.host,
                                port = request.port,
                                username = request.username,
                                password = password,
                                path = request.path,
                                onProgress = progress,
                                onFile = handle,
                            )
                        }
                    request is ImportRequest.SftpRequest && request.directory ->
                        importFolderSession(
                            deviceId = deviceId,
                            clearBeforeImport = request.clearBeforeImport,
                            onProgress = onProgress,
                        ) { handle, progress ->
                            val password = resolvePassword(request.password, request.passwordCredentialId)
                            importers.forEachCsvInSftpFolder(
                                host = request.host,
                                port = request.port,
                                username = request.username,
                                password = password,
                                path = request.path,
                                onProgress = progress,
                                onFile = handle,
                            )
                        }
                    else -> importSingleSources(deviceId, request, onProgress)
                }

                repository.completeImportJob(
                    jobId = jobId,
                    success = true,
                    message = outcome.message,
                    copyPath = outcome.copyPath,
                )
                cloudBackupCoordinator.enqueue(BackupTrigger.Auto)
            } catch (t: Throwable) {
                repository.completeImportJob(jobId, false, t.message, null)
                throw t
            }
        }
    }

    /**
     * Folder imports parse and persist one CSV at a time so ~9000 files stay within heap.
     * Preserved local copies are skipped for folder imports (too much storage).
     *
     * History clear (if requested) runs only after a CSV parses to usable records.
     * Hour aggregates are recomputed once at the end for the imported spot range.
     */
    private suspend fun importFolderSession(
        deviceId: Long,
        clearBeforeImport: Boolean,
        onProgress: ((current: Int, total: Int) -> Unit)?,
        walk: (
            onFile: (name: String, bytes: ByteArray) -> Unit,
            onProgress: ((current: Int, total: Int) -> Unit)?,
        ) -> Int,
    ): ImportOutcome {
        var spot = 0
        var days = 0
        var months = 0
        var events = 0
        var committedFiles = 0
        var historyCleared = false
        var spotFrom: Long? = null
        var spotTo: Long? = null
        var lastHourRecomputeTo: Long? = null
        val bridgeContext = coroutineContext
        try {
            // Synchronous FTP/SFTP session APIs require a bridge to Room suspend calls.
            // runBlocking(coroutineContext) inherits cancellation from this IO worker.
            val fileCount = walk(
                { name, bytes ->
                    runBlocking(bridgeContext) {
                        val parsed = importers.parseFile(deviceId, name, bytes)
                        val hasData = parsed.spotSamples.isNotEmpty() ||
                            parsed.dayAggregates.isNotEmpty() ||
                            parsed.monthAggregates.isNotEmpty() ||
                            parsed.events.isNotEmpty()
                        if (!hasData) return@runBlocking
                        if (clearBeforeImport && !historyCleared) {
                            repository.clearDeviceHistory(deviceId)
                            historyCleared = true
                        }
                        repository.importBundle(
                            spotSamples = parsed.spotSamples,
                            dayAggregates = parsed.dayAggregates,
                            monthAggregates = parsed.monthAggregates,
                            events = parsed.events,
                            recomputeHours = false,
                        )
                        if (parsed.spotSamples.isNotEmpty()) {
                            val minTs = parsed.spotSamples.minOf { it.timestampEpochSeconds }
                            val maxTs = parsed.spotSamples.maxOf { it.timestampEpochSeconds }
                            spotFrom = minOf(spotFrom ?: minTs, minTs)
                            spotTo = maxOf(spotTo ?: maxTs, maxTs)
                        }
                        spot += parsed.spotSamples.size
                        days += parsed.dayAggregates.size
                        months += parsed.monthAggregates.size
                        events += parsed.events.size
                        committedFiles += 1
                        // Checkpoint only newly covered time so large imports stay linear.
                        val from = spotFrom
                        val to = spotTo
                        if (from != null && to != null && committedFiles % 25 == 0) {
                            val recomputeFrom = (lastHourRecomputeTo?.plus(1)) ?: from
                            if (recomputeFrom <= to) {
                                repository.recomputeHourAggregates(deviceId, recomputeFrom, to)
                                lastHourRecomputeTo = to
                            }
                        }
                    }
                },
                onProgress,
            )
            require(fileCount > 0) { "No importable files found" }
            require(committedFiles > 0) { "No usable CSV data found under folder import" }
            val from = spotFrom
            val to = spotTo
            if (from != null && to != null) {
                repository.recomputeHourAggregates(deviceId, from, to)
            }
            return ImportOutcome(
                message = "Imported $committedFiles file(s): $spot spot samples, $days days, $months months, $events events",
            )
        } catch (t: Throwable) {
            val from = spotFrom
            val to = spotTo
            if (from != null && to != null) {
                runCatching {
                    runBlocking(bridgeContext) {
                        repository.recomputeHourAggregates(deviceId, from, to)
                    }
                }
            }
            val prefix = buildString {
                if (historyCleared) append("Device history was cleared. ")
                if (committedFiles > 0) {
                    append("Partial import: $committedFiles file(s) already saved. ")
                }
            }
            if (committedFiles > 0) {
                cloudBackupCoordinator.enqueue(BackupTrigger.Auto)
            }
            if (prefix.isEmpty()) throw t
            throw IllegalStateException(prefix + (t.message ?: "Import failed"), t)
        }
    }

    private suspend fun importSingleSources(
        deviceId: Long,
        request: ImportRequest,
        onProgress: ((current: Int, total: Int) -> Unit)?,
    ): ImportOutcome {
        var fileCount = 0
        var spot = 0
        var days = 0
        var months = 0
        var events = 0
        var lastPreserved: String? = null
        var historyCleared = false
        val clearBeforeImport = request.clearBeforeImport
        val stagedCopies = mutableListOf<Pair<String, java.io.File>>()
        try {
            try {
                forEachDownload(request) { name, bytes ->
                    fileCount++
                    onProgress?.invoke(fileCount, fileCount)
                    val parsed = importers.parseFile(deviceId, name, bytes)
                    val hasData = parsed.spotSamples.isNotEmpty() ||
                        parsed.dayAggregates.isNotEmpty() ||
                        parsed.monthAggregates.isNotEmpty() ||
                        parsed.events.isNotEmpty()
                    require(hasData) { "Import file $name contained no usable data" }
                    if (clearBeforeImport && !historyCleared) {
                        repository.clearDeviceHistory(deviceId)
                        historyCleared = true
                    }
                    val staged = java.io.File.createTempFile("import-", ".bin", appContext.cacheDir)
                    staged.writeBytes(bytes)
                    stagedCopies += parsed.preservedName to staged
                    spot += parsed.spotSamples.size
                    days += parsed.dayAggregates.size
                    months += parsed.monthAggregates.size
                    events += parsed.events.size
                    repository.importBundle(
                        spotSamples = parsed.spotSamples,
                        dayAggregates = parsed.dayAggregates,
                        monthAggregates = parsed.monthAggregates,
                        events = parsed.events,
                    )
                }
                require(fileCount > 0) { "No importable files found" }
                for ((preservedName, staged) in stagedCopies) {
                    lastPreserved = repository.storeImportedCopy(preservedName, staged.readBytes())
                }
            } catch (t: Throwable) {
                val prefix = buildString {
                    if (historyCleared) append("Device history was cleared. ")
                    if (fileCount > 0) append("Partial import: $fileCount file(s) already saved. ")
                }
                if (prefix.isEmpty()) throw t
                throw IllegalStateException(prefix + (t.message ?: "Import failed"), t)
            }
        } finally {
            stagedCopies.forEach { (_, file) -> runCatching { file.delete() } }
        }
        return ImportOutcome(
            message = "Imported $fileCount file(s): $spot spot samples, $days days, $months months, $events events",
            copyPath = lastPreserved,
        )
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
                val (name, bytes) = importers.downloadFtp(
                    host = request.host,
                    port = request.port,
                    username = request.username,
                    password = password,
                    path = request.path,
                )
                accept(name, bytes)
            }
            is ImportRequest.SftpRequest -> {
                val password = resolvePassword(request.password, request.passwordCredentialId)
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

    private fun resolvePassword(inlinePassword: String?, credentialId: String?): String {
        // Empty string is intentional (e.g. anonymous FTP); only null falls back to credentials.
        if (inlinePassword != null) return inlinePassword
        return credentialStore.getSecret(credentialId)
            ?: error("Missing import password credential")
    }
}
