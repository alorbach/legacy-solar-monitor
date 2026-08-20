package com.alorbach.solarmonitor.data.importing

import com.alorbach.solarmonitor.data.model.ImportJobEntity
import com.alorbach.solarmonitor.data.model.ImportSourceType

data class ImportJobGroup(
    val key: String,
    val deviceId: Long?,
    val sourceLabel: String,
    val latest: ImportJobEntity,
    val history: List<ImportJobEntity>,
)

fun publicUrlSourceLabel(url: String): String {
    val trimmed = url.trim()
    val uri = runCatching { java.net.URI(trimmed) }.getOrNull()
    val scheme = uri?.scheme?.lowercase()
    val host = uri?.host
    if (scheme != null && host != null) {
        val port = if (uri.port >= 0) ":${uri.port}" else ""
        val path = uri.rawPath.orEmpty().ifBlank { "/" }
        return "$scheme://$host$port$path"
    }
    val withoutQuery = trimmed.substringBefore('#').substringBefore('?')
    val schemeSep = withoutQuery.indexOf("://")
    if (schemeSep < 0) return "URL import"
    val schemePart = withoutQuery.substring(0, schemeSep).ifBlank { return "URL import" }
    val rest = withoutQuery.substring(schemeSep + 3)
    val authorityAndPath = if ('@' in rest) rest.substringAfter('@') else rest
    if (authorityAndPath.isBlank()) return "URL import"
    return "$schemePart://$authorityAndPath"
}

fun groupImportJobs(jobs: List<ImportJobEntity>): List<ImportJobGroup> {
    return jobs
        .groupBy {
            val label = it.sourceLabel.trim()
            val grouped = if (it.sourceType == ImportSourceType.URL) {
                publicUrlSourceLabel(label)
            } else {
                label
            }
            (it.deviceId ?: -1L) to grouped
        }
        .map { (pair, groupJobs) ->
            val sorted = groupJobs.sortedByDescending { job ->
                job.completedAtEpochSeconds ?: job.createdAtEpochSeconds
            }
            ImportJobGroup(
                key = "${pair.first}\u0000${pair.second}",
                deviceId = pair.first.takeIf { it > 0L },
                sourceLabel = pair.second.ifBlank { sorted.first().sourceLabel },
                latest = sorted.first(),
                history = sorted.drop(1),
            )
        }
        .sortedByDescending { it.latest.completedAtEpochSeconds ?: it.latest.createdAtEpochSeconds }
}
