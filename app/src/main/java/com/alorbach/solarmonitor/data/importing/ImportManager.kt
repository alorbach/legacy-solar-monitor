package com.alorbach.solarmonitor.data.importing

import android.content.Context
import com.alorbach.solarmonitor.data.model.ImportJobEntity
import com.alorbach.solarmonitor.data.model.ImportJobStatus
import com.alorbach.solarmonitor.data.repository.SolarRepository

class ImportManager(
    private val appContext: Context,
    private val repository: SolarRepository,
    private val importers: LegacySbfspotImporters,
) {
    suspend fun run(request: ImportRequest): Result<Unit> = runCatching {
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
            val (name, bytes) = when (request) {
                is ImportRequest.FileRequest -> importers.readUri(request.uri)
                is ImportRequest.UrlRequest -> importers.downloadUrl(request.url)
                is ImportRequest.FtpRequest -> importers.downloadFtp(request.host, request.username, request.password, request.path)
                is ImportRequest.SftpRequest -> importers.downloadSftp(request.host, request.username, request.password, request.path)
            }

            val parsed = importers.parseFile(deviceId, name, bytes)
            repository.saveSpotSamples(parsed.spotSamples)
            repository.saveDayAggregates(parsed.dayAggregates)
            repository.saveMonthAggregates(parsed.monthAggregates)
            repository.saveEvents(parsed.events)
            val preserved = repository.storeImportedCopy(parsed.preservedName, bytes)
            repository.completeImportJob(
                jobId = jobId,
                success = true,
                message = "Imported ${parsed.spotSamples.size} spot samples, ${parsed.dayAggregates.size} days, ${parsed.monthAggregates.size} months, ${parsed.events.size} events",
                copyPath = preserved,
            )
        } catch (t: Throwable) {
            repository.completeImportJob(jobId, false, t.message, null)
            throw t
        }
    }
}
