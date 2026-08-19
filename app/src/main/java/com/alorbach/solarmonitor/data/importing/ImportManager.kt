package com.alorbach.solarmonitor.data.importing

import android.content.Context
import androidx.core.content.ContextCompat
import com.alorbach.solarmonitor.R
import com.alorbach.solarmonitor.data.cloud.BackupTrigger
import com.alorbach.solarmonitor.data.cloud.CloudBackupCoordinator
import com.alorbach.solarmonitor.data.model.ImportJobEntity
import com.alorbach.solarmonitor.data.model.ImportJobStatus
import com.alorbach.solarmonitor.data.repository.SolarRepository
import com.alorbach.solarmonitor.data.security.CredentialStore
import com.alorbach.solarmonitor.service.ImportForegroundService
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

class ImportAlreadyRunningException(message: String) : IllegalStateException(message)

data class ImportProgress(
    val running: Boolean = false,
    val current: Int = 0,
    val total: Int = 0,
    val generation: Long = 0L,
    val lastMessage: String? = null,
    val lastSuccess: Boolean? = null,
)

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

    data class PendingForegroundImport(
        val request: ImportRequest,
        val overwriteCopyPath: String?,
    )

    private val importBusy = AtomicBoolean(false)
    private val _progress = MutableStateFlow(ImportProgress())
    val progress: StateFlow<ImportProgress> = _progress.asStateFlow()

    @Volatile private var pendingForeground: PendingForegroundImport? = null
    @Volatile private var foregroundReserved = false

    /**
     * Starts a data-sync foreground service so folder/FTP imports keep running
     * after the screen turns off or the activity stops.
     */
    fun startForegroundImport(
        context: Context,
        request: ImportRequest,
        overwriteCopyPath: String? = null,
    ): Boolean {
        if (!importBusy.compareAndSet(false, true)) return false
        foregroundReserved = true
        pendingForeground = PendingForegroundImport(request, overwriteCopyPath)
        return try {
            ContextCompat.startForegroundService(
                context.applicationContext,
                ImportForegroundService.startIntent(context.applicationContext),
            )
            true
        } catch (_: Throwable) {
            pendingForeground = null
            foregroundReserved = false
            importBusy.set(false)
            false
        }
    }

    fun takePendingForegroundImport(): PendingForegroundImport? {
        val pending = pendingForeground
        pendingForeground = null
        return pending
    }

    fun abortForegroundReservation() {
        pendingForeground = null
        if (!_progress.value.running) {
            foregroundReserved = false
            importBusy.set(false)
        }
    }

    fun forceReleaseReservation() {
        pendingForeground = null
        foregroundReserved = false
        importBusy.set(false)
    }

    suspend fun run(
        request: ImportRequest,
        overwriteCopyPath: String? = null,
        consumeForegroundReservation: Boolean = false,
        onProgress: ((current: Int, total: Int) -> Unit)? = null,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (consumeForegroundReservation) {
            foregroundReserved = false
        } else if (!importBusy.compareAndSet(false, true)) {
            return@withContext Result.failure(
                ImportAlreadyRunningException(appContext.getString(R.string.import_already_running)),
            )
        }
        _progress.value = ImportProgress(
            running = true,
            generation = _progress.value.generation,
        )
        var successMessage: String? = null
        val bridgedProgress: (Int, Int) -> Unit = { current, total ->
            _progress.update { it.copy(current = current, total = total) }
            onProgress?.invoke(current, total)
        }
        try {
            runCatching {
            val deviceId = requireNotNull(request.deviceId) { appContext.getString(R.string.import_device_required) }
            val replay = ImportReplayConfig.fromRequest(request)?.let { config ->
                if (request is ImportRequest.UrlRequest &&
                    ImportReplayConfig.isSensitiveImportUrl(request.url)
                ) {
                    // Keep kind/device/label for re-run UI; secret URL lives in CredentialStore.
                    config.copy(url = null)
                } else {
                    config
                }
            }
            val needsNewSecret = when (request) {
                is ImportRequest.FtpRequest -> request.password != null
                is ImportRequest.SftpRequest -> request.password != null
                is ImportRequest.UrlRequest -> ImportReplayConfig.isSensitiveImportUrl(request.url)
                else -> false
            }
            val plannedCredentialId = when (request) {
                is ImportRequest.FtpRequest -> when {
                    request.password != null ->
                        request.passwordCredentialId?.takeIf { it.isNotBlank() }
                            ?: credentialStore.allocateSecretId()
                    else -> request.passwordCredentialId?.takeIf { it.isNotBlank() }
                }
                is ImportRequest.SftpRequest -> when {
                    request.password != null ->
                        request.passwordCredentialId?.takeIf { it.isNotBlank() }
                            ?: credentialStore.allocateSecretId()
                    else -> request.passwordCredentialId?.takeIf { it.isNotBlank() }
                }
                is ImportRequest.UrlRequest ->
                    if (ImportReplayConfig.isSensitiveImportUrl(request.url)) {
                        credentialStore.allocateSecretId()
                    } else {
                        null
                    }
                else -> null
            }
            // Persist secrets before the history row so a crash cannot leave a job
            // advertising a reusable credential that does not exist yet.
            if (needsNewSecret && plannedCredentialId != null) {
                when (request) {
                    is ImportRequest.FtpRequest ->
                        credentialStore.putSecret(requireNotNull(request.password), plannedCredentialId)
                    is ImportRequest.SftpRequest ->
                        credentialStore.putSecret(requireNotNull(request.password), plannedCredentialId)
                    is ImportRequest.UrlRequest ->
                        credentialStore.putSecret(request.url, plannedCredentialId)
                    else -> Unit
                }
            }
            val jobId = try {
                repository.recordImportJob(
                    ImportJobEntity(
                        deviceId = deviceId,
                        sourceLabel = request.sourceLabel,
                        sourceType = request.sourceType,
                        status = ImportJobStatus.RUNNING,
                        createdAtEpochSeconds = System.currentTimeMillis() / 1000,
                        replayConfigJson = replay?.toJson(),
                        passwordCredentialId = plannedCredentialId,
                    )
                )
            } catch (t: Throwable) {
                if (needsNewSecret) {
                    plannedCredentialId?.let { repository.reclaimOrphanImportCredential(it) }
                }
                throw t
            }
            try {
                val outcome = when {
                    request is ImportRequest.FtpRequest && request.directory ->
                        importFolderSession(
                            deviceId = deviceId,
                            clearBeforeImport = request.clearBeforeImport,
                            onProgress = bridgedProgress,
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
                            onProgress = bridgedProgress,
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
                    else -> importSingleSources(deviceId, request, bridgedProgress, overwriteCopyPath)
                }

                repository.completeImportJob(
                    jobId = jobId,
                    success = true,
                    message = outcome.message,
                    copyPath = outcome.copyPath,
                )
                successMessage = outcome.message
                cloudBackupCoordinator.enqueue(BackupTrigger.Auto)
                runCatching { com.alorbach.solarmonitor.widget.SolarWidgets.refreshAll(appContext) }
                Unit
            } catch (t: Throwable) {
                repository.completeImportJob(jobId, false, t.message, null)
                runCatching { com.alorbach.solarmonitor.widget.SolarWidgets.refreshAll(appContext) }
                throw t
            }
        }.also { result ->
            _progress.update {
                it.copy(
                    running = false,
                    current = 0,
                    total = 0,
                    generation = it.generation + 1,
                    lastMessage = result.exceptionOrNull()?.message ?: successMessage,
                    lastSuccess = result.isSuccess,
                )
            }
        }
        } finally {
            importBusy.set(false)
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
            require(fileCount > 0) { appContext.getString(R.string.import_no_files) }
            require(committedFiles > 0) { appContext.getString(R.string.import_no_usable_folder) }
            val from = spotFrom
            val to = spotTo
            if (from != null && to != null) {
                repository.recomputeHourAggregates(deviceId, from, to)
            }
            return ImportOutcome(
                message = appContext.getString(
                    R.string.import_result,
                    committedFiles,
                    spot,
                    days,
                    months,
                    events,
                ),
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
                if (historyCleared) append(appContext.getString(R.string.import_history_cleared)).append(' ')
                if (committedFiles > 0) {
                    append(appContext.getString(R.string.import_partial, committedFiles)).append(' ')
                }
            }
            if (committedFiles > 0) {
                cloudBackupCoordinator.enqueue(BackupTrigger.Auto)
            }
            if (prefix.isEmpty()) throw t
            throw IllegalStateException(prefix + (t.message ?: appContext.getString(R.string.import_failed_generic)), t)
        }
    }

    private suspend fun importSingleSources(
        deviceId: Long,
        request: ImportRequest,
        onProgress: ((current: Int, total: Int) -> Unit)?,
        overwriteCopyPath: String? = null,
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
                    require(hasData) { appContext.getString(R.string.import_file_no_data, name) }
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
                require(fileCount > 0) { appContext.getString(R.string.import_no_files) }
                for ((preservedName, staged) in stagedCopies) {
                    lastPreserved = repository.storeImportedCopy(
                        deviceId = deviceId,
                        relativeName = preservedName,
                        bytes = staged.readBytes(),
                        overwritePath = overwriteCopyPath?.takeIf { stagedCopies.size == 1 },
                    )
                }
            } catch (t: Throwable) {
                val prefix = buildString {
                    if (historyCleared) append(appContext.getString(R.string.import_history_cleared)).append(' ')
                    if (fileCount > 0) append(appContext.getString(R.string.import_partial, fileCount)).append(' ')
                }
                if (prefix.isEmpty()) throw t
                throw IllegalStateException(prefix + (t.message ?: appContext.getString(R.string.import_failed_generic)), t)
            }
        } finally {
            stagedCopies.forEach { (_, file) -> runCatching { file.delete() } }
        }
        return ImportOutcome(
            message = appContext.getString(
                R.string.import_result,
                fileCount,
                spot,
                days,
                months,
                events,
            ),
            copyPath = lastPreserved,
        )
    }

    private suspend fun forEachDownload(
        request: ImportRequest,
        handle: suspend (name: String, bytes: ByteArray) -> Unit,
    ) {
        suspend fun accept(name: String, bytes: ByteArray) {
            require(bytes.size <= RemoteBrowseHelpers.MAX_IMPORT_FILE_BYTES) {
                appContext.getString(
                    R.string.import_file_too_large,
                    name,
                    (RemoteBrowseHelpers.MAX_IMPORT_FILE_BYTES / (1024 * 1024)).toInt(),
                )
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
            ?: error(appContext.getString(R.string.import_password_missing))
    }

    /**
     * Build an [ImportRequest] from a history job for re-run.
     * Prefer stored credentials; optional overrides cover legacy label-only jobs.
     */
    fun replayRequest(
        job: ImportJobEntity,
        usernameOverride: String? = null,
        passwordOverride: String? = null,
        portOverride: Int? = null,
        urlOverride: String? = null,
    ): ImportRequest {
        val config = job.replayConfig() ?: error(appContext.getString(R.string.import_not_rerunnable))
        val effective = if (usernameOverride != null) config.copy(username = usernameOverride) else config
        return when {
            passwordOverride != null ->
                effective.toRequest(
                    password = passwordOverride,
                    passwordCredentialId = null,
                    portOverride = portOverride,
                )
            effective.kind == "URL" -> {
                val url = urlOverride?.takeIf { it.isNotBlank() }
                    ?: effective.url
                    ?: job.passwordCredentialId?.let { credentialStore.getSecret(it) }
                    ?: error(appContext.getString(R.string.import_url_missing))
                ImportRequest.UrlRequest(
                    deviceId = effective.deviceId,
                    url = url,
                    sourceLabel = effective.sourceLabel,
                )
            }
            !job.passwordCredentialId.isNullOrBlank() &&
                credentialStore.getSecret(job.passwordCredentialId) != null ->
                effective.toRequest(
                    password = null,
                    passwordCredentialId = job.passwordCredentialId,
                    portOverride = portOverride,
                )
            else -> error(appContext.getString(R.string.import_password_required))
        }
    }
}
