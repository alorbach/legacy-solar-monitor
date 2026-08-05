package com.alorbach.solarmonitor.data.importing

import android.net.Uri
import com.alorbach.solarmonitor.data.model.DayAggregateEntity
import com.alorbach.solarmonitor.data.model.DeviceEventEntity
import com.alorbach.solarmonitor.data.model.ImportSourceType
import com.alorbach.solarmonitor.data.model.MonthAggregateEntity
import com.alorbach.solarmonitor.data.model.SpotSampleEntity

data class ParsedImportBundle(
    val spotSamples: List<SpotSampleEntity> = emptyList(),
    val dayAggregates: List<DayAggregateEntity> = emptyList(),
    val monthAggregates: List<MonthAggregateEntity> = emptyList(),
    val events: List<DeviceEventEntity> = emptyList(),
    val preservedName: String,
    val sourceType: ImportSourceType,
) {
    operator fun plus(other: ParsedImportBundle): ParsedImportBundle =
        ParsedImportBundle(
            spotSamples = spotSamples + other.spotSamples,
            dayAggregates = dayAggregates + other.dayAggregates,
            monthAggregates = monthAggregates + other.monthAggregates,
            events = events + other.events,
            preservedName = preservedName,
            sourceType = sourceType,
        )
}

sealed interface ImportRequest {
    val deviceId: Long?
    val sourceLabel: String
    val sourceType: ImportSourceType

    data class FileRequest(
        override val deviceId: Long?,
        val uri: Uri,
        override val sourceLabel: String,
        override val sourceType: ImportSourceType = ImportSourceType.FILE,
    ) : ImportRequest

    data class UrlRequest(
        override val deviceId: Long?,
        val url: String,
        override val sourceLabel: String = url,
        override val sourceType: ImportSourceType = ImportSourceType.URL,
    ) : ImportRequest

    data class FtpRequest(
        override val deviceId: Long?,
        val host: String,
        val username: String,
        val password: String,
        val path: String,
        override val sourceLabel: String,
        override val sourceType: ImportSourceType = ImportSourceType.FTP,
    ) : ImportRequest

    data class SftpRequest(
        override val deviceId: Long?,
        val host: String,
        val username: String,
        val password: String,
        val path: String,
        override val sourceLabel: String,
        override val sourceType: ImportSourceType = ImportSourceType.SFTP,
    ) : ImportRequest
}
