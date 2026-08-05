package com.alorbach.solarmonitor.data.importing

import com.alorbach.solarmonitor.data.model.DayAggregateEntity
import com.alorbach.solarmonitor.data.model.DeviceEventEntity
import com.alorbach.solarmonitor.data.model.ImportSourceType
import com.alorbach.solarmonitor.data.model.MonthAggregateEntity
import com.alorbach.solarmonitor.data.model.SpotSampleEntity
import java.io.BufferedReader
import java.io.InputStream
import java.nio.charset.Charset
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class SbfspotCsvParser(
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {
    fun parse(deviceId: Long, name: String, inputStream: InputStream): ParsedImportBundle {
        val text = inputStream.bufferedReader(Charset.forName("UTF-8")).use(BufferedReader::readLines)
            .ifEmpty { emptyList() }

        if (text.isEmpty()) {
            return ParsedImportBundle(preservedName = name, sourceType = ImportSourceType.FILE)
        }

        val normalized = text.filter { it.isNotBlank() }
        return when {
            normalized.any { it.startsWith("DeviceType;DeviceLocation;SusyId;SerNo") } -> parseEvents(deviceId, name, normalized)
            normalized.any { it.startsWith("dd/MM/yyyy;") || it.startsWith("dd.MM.yyyy;") } -> parseMonth(deviceId, name, normalized)
            normalized.any { it.startsWith("dd/MM/yyyy HH:mm;") || it.startsWith("dd.MM.yyyy HH:mm;") } -> parseDay(deviceId, name, normalized)
            else -> ParsedImportBundle(preservedName = name, sourceType = ImportSourceType.FILE)
        }
    }

    private fun parseMonth(deviceId: Long, name: String, lines: List<String>): ParsedImportBundle {
        val headerIndex = lines.indexOfFirst { it.startsWith("dd/MM/yyyy;") || it.startsWith("dd.MM.yyyy;") }
        if (headerIndex < 0) return ParsedImportBundle(preservedName = name, sourceType = ImportSourceType.FILE)
        val dateFormatter = if (lines[headerIndex].startsWith("dd.MM")) {
            DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.GERMANY)
        } else {
            DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.US)
        }
        val entries = lines.drop(headerIndex + 1).mapNotNull { line ->
            val parts = line.split(';')
            if (parts.size < 3) return@mapNotNull null
            val date = LocalDate.parse(parts[0], dateFormatter)
            MonthAggregateEntity(
                deviceId = deviceId,
                monthKey = YearMonth.from(date).toString(),
                totalYieldWh = parseDecimalKwh(parts[1]),
                dayYieldWh = parseDecimalKwh(parts[2]),
            )
        }.distinctBy { it.monthKey }
        return ParsedImportBundle(
            monthAggregates = entries,
            preservedName = name,
            sourceType = ImportSourceType.FILE,
        )
    }

    private fun parseDay(deviceId: Long, name: String, lines: List<String>): ParsedImportBundle {
        val headerIndex = lines.indexOfFirst { it.startsWith("dd/MM/yyyy HH:mm;") || it.startsWith("dd.MM.yyyy HH:mm;") }
        if (headerIndex < 0) return ParsedImportBundle(preservedName = name, sourceType = ImportSourceType.FILE)
        val formatter = if (lines[headerIndex].startsWith("dd.MM")) {
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", Locale.GERMANY)
        } else {
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.US)
        }
        val spotSamples = lines.drop(headerIndex + 1).mapNotNull { line ->
            val parts = line.split(';')
            if (parts.size < 3) return@mapNotNull null
            val dateTime = LocalDateTime.parse(parts[0], formatter)
            val eTotalWh = parseDecimalKwh(parts[1])
            val totalPac = parseDecimalKw(parts[2])
            SpotSampleEntity(
                deviceId = deviceId,
                timestampEpochSeconds = dateTime.atZone(zoneId).toEpochSecond(),
                totalPac = totalPac,
                eTotalWh = eTotalWh,
                status = "Imported",
                sourceType = "day_csv",
            )
        }
        val grouped = spotSamples.groupBy { Instant.ofEpochSecond(it.timestampEpochSeconds).atZone(zoneId).toLocalDate() }
        val days = grouped.map { (date, items) ->
            DayAggregateEntity(
                deviceId = deviceId,
                dateEpochDay = date.toEpochDay(),
                totalYieldWh = items.maxOfOrNull { it.eTotalWh ?: 0 } ?: 0,
                powerW = items.maxOfOrNull { it.totalPac ?: 0 },
            )
        }
        return ParsedImportBundle(
            spotSamples = spotSamples,
            dayAggregates = days,
            preservedName = name,
            sourceType = ImportSourceType.FILE,
        )
    }

    private fun parseEvents(deviceId: Long, name: String, lines: List<String>): ParsedImportBundle {
        val formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss", Locale.US)
        val headerIndex = lines.indexOfFirst { it.startsWith("DeviceType;DeviceLocation;SusyId;SerNo") }
        if (headerIndex < 0) return ParsedImportBundle(preservedName = name, sourceType = ImportSourceType.FILE)
        val events = lines.drop(headerIndex + 1).mapNotNull { line ->
            val parts = line.split(';')
            if (parts.size < 14) return@mapNotNull null
            val ts = LocalDateTime.parse(parts[4], formatter)
            DeviceEventEntity(
                deviceId = deviceId,
                entryId = parts[5].toLongOrNull() ?: return@mapNotNull null,
                timestampEpochSeconds = ts.atZone(zoneId).toEpochSecond(),
                eventCode = parts[6].toIntOrNull() ?: 0,
                eventType = parts[7],
                category = parts[8],
                eventGroup = parts[9],
                tag = parts[10],
                oldValue = parts[11],
                newValue = parts[12],
                userGroup = parts[13],
            )
        }
        return ParsedImportBundle(
            events = events,
            preservedName = name,
            sourceType = ImportSourceType.FILE,
        )
    }

    private fun parseDecimalKwh(value: String): Long =
        ((value.trim().replace(".", "").replace(',', '.').toDoubleOrNull() ?: 0.0) * 1000.0).toLong()

    private fun parseDecimalKw(value: String): Int =
        ((value.trim().replace(".", "").replace(',', '.').toDoubleOrNull() ?: 0.0) * 1000.0).toInt()
}
