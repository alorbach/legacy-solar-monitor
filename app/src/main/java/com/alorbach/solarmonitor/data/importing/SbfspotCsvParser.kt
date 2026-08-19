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

data class CsvParseOptions(
    val zoneId: ZoneId = ZoneId.systemDefault(),
    val decimalPoint: String = "comma",
    val delimiter: String = "semicolon",
    val dateFormat: String? = null,
)

object CsvFormat {
    fun normalizeDecimalPoint(value: String): String =
        if (value.trim().equals("comma", ignoreCase = true)) "comma" else "point"

    fun normalizeDelimiter(value: String): String =
        if (value.trim().equals("comma", ignoreCase = true)) "comma" else "semicolon"
}

class SbfspotCsvParser(
    private val options: CsvParseOptions = CsvParseOptions(),
) {
    constructor(zoneId: ZoneId) : this(CsvParseOptions(zoneId = zoneId))

    fun parse(deviceId: Long, name: String, inputStream: InputStream): ParsedImportBundle {
        val text = readLines(inputStream)
        if (text.isEmpty()) {
            return ParsedImportBundle(preservedName = name, sourceType = ImportSourceType.FILE)
        }

        val metadata = detectMetadata(text)
        val normalized = text.filter { it.isNotBlank() }
        return when {
            normalized.any { it.startsWith("DeviceType;DeviceLocation;SusyId;SerNo") ||
                it.startsWith("DeviceType,DeviceLocation,SusyId,SerNo") } ->
                parseEvents(deviceId, name, normalized, metadata)
            normalized.any { headerDatePattern(it, withTime = true) != null } ->
                parseDay(deviceId, name, normalized, metadata)
            normalized.any { headerDatePattern(it, withTime = false) != null } ->
                parseMonth(deviceId, name, normalized, metadata)
            else -> ParsedImportBundle(preservedName = name, sourceType = ImportSourceType.FILE)
        }
    }

    /**
     * SBFspot writes its configured date pattern into the first header cell, so the export
     * describes its own date format instead of it having to be guessed. Returns that pattern when
     * the line is the header of a date-keyed table, or null otherwise.
     */
    private fun headerDatePattern(line: String, withTime: Boolean): String? {
        val first = line.split(';', ',').firstOrNull()?.trim().orEmpty()
        if (!first.contains("yyyy")) return null
        if (first.contains("HH") != withTime) return null
        return first
    }

    /** Falls back to the profile's configured format for exports Java cannot compile a pattern from. */
    private fun dateFormatter(headerPattern: String): DateTimeFormatter? =
        compilePattern(headerPattern) ?: options.dateFormat?.let { compilePattern(it) }

    private fun compilePattern(pattern: String): DateTimeFormatter? =
        runCatching { DateTimeFormatter.ofPattern(pattern, Locale.ROOT) }.getOrNull()

    private fun readLines(inputStream: InputStream): List<String> {
        val bytes = readBounded(inputStream, MAX_CSV_BYTES)
        val utf8 = runCatching {
            bytes.inputStream().bufferedReader(Charset.forName("UTF-8")).use(BufferedReader::readLines)
        }.getOrDefault(emptyList())
        if (utf8.isNotEmpty() && utf8.none { it.contains('\uFFFD') }) return utf8
        return bytes.inputStream().bufferedReader(Charset.forName("Windows-1252")).use(BufferedReader::readLines)
    }

    private fun readBounded(inputStream: InputStream, maxBytes: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val n = inputStream.read(buf)
            if (n < 0) break
            total += n
            require(total <= maxBytes) {
                "CSV import exceeds ${maxBytes / (1024 * 1024)} MiB limit"
            }
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun detectMetadata(lines: List<String>): CsvMetadata {
        val header = lines.firstOrNull { it.startsWith("Version CSV") }.orEmpty()
        val decimalFromHeader = when {
            header.contains("Decimalpoint comma", ignoreCase = true) -> "comma"
            header.contains("Decimalpoint point", ignoreCase = true) -> "point"
            else -> CsvFormat.normalizeDecimalPoint(options.decimalPoint)
        }
        val delimiterFromHeader = when {
            header.contains("Delimiter semicolon", ignoreCase = true) -> ';'
            header.contains("Delimiter comma", ignoreCase = true) -> ','
            CsvFormat.normalizeDelimiter(options.delimiter) == "comma" -> ','
            else -> ';'
        }
        return CsvMetadata(
            decimalPoint = decimalFromHeader,
            delimiter = delimiterFromHeader,
            zoneId = options.zoneId,
        )
    }

    private fun parseMonth(
        deviceId: Long,
        name: String,
        lines: List<String>,
        metadata: CsvMetadata,
    ): ParsedImportBundle {
        val headerIndex = lines.indexOfFirst { headerDatePattern(it, withTime = false) != null }
        if (headerIndex < 0) return ParsedImportBundle(preservedName = name, sourceType = ImportSourceType.FILE)
        val dateFormatter = headerDatePattern(lines[headerIndex], withTime = false)
            ?.let { dateFormatter(it) }
            ?: return ParsedImportBundle(preservedName = name, sourceType = ImportSourceType.FILE)
        val entries = lines.drop(headerIndex + 1).mapNotNull { line ->
            val parts = line.split(metadata.delimiter)
            if (parts.size < 3) return@mapNotNull null
            val date = runCatching { LocalDate.parse(parts[0], dateFormatter) }.getOrNull()
                ?: return@mapNotNull null
            val parsedDayYield = parseDecimal(parts[2], metadata.decimalPoint)
            MonthRow(
                date = date,
                totalYieldWh = parseDecimalKwh(parts[1], metadata.decimalPoint),
                dayYieldWh = parsedDayYield?.let { (it * 1000.0).toLong() },
            )
        }
        // Keep the latest day row per month (cumulative total + sum of day yields)
        val monthAggregates = entries
            .groupBy { YearMonth.from(it.date) }
            .map { (month, rows) ->
                val ordered = rows.sortedBy { it.date }
                MonthAggregateEntity(
                    deviceId = deviceId,
                    monthKey = month.toString(),
                    totalYieldWh = ordered.last().totalYieldWh,
                    dayYieldWh = ordered.sumOf { it.dayYieldWh ?: 0L },
                )
            }
        // Month CSVs are one row per calendar day with an explicit DayYield column.
        val dayAggregates = entries.mapNotNull { row ->
            val dayYieldWh = row.dayYieldWh ?: return@mapNotNull null
            DayAggregateEntity(
                deviceId = deviceId,
                dateEpochDay = row.date.toEpochDay(),
                totalYieldWh = dayYieldWh,
                sourceType = "month_csv",
            )
        }
        return ParsedImportBundle(
            dayAggregates = dayAggregates,
            monthAggregates = monthAggregates,
            preservedName = name,
            sourceType = ImportSourceType.FILE,
        )
    }

    private fun parseDay(
        deviceId: Long,
        name: String,
        lines: List<String>,
        metadata: CsvMetadata,
    ): ParsedImportBundle {
        val headerIndex = lines.indexOfFirst { headerDatePattern(it, withTime = true) != null }
        if (headerIndex < 0) return ParsedImportBundle(preservedName = name, sourceType = ImportSourceType.FILE)
        val formatter = headerDatePattern(lines[headerIndex], withTime = true)
            ?.let { dateFormatter(it) }
            ?: return ParsedImportBundle(preservedName = name, sourceType = ImportSourceType.FILE)
        val spotSamples = lines.drop(headerIndex + 1).mapNotNull { line ->
            val parts = line.split(metadata.delimiter)
            if (parts.size < 3) return@mapNotNull null
            val dateTime = runCatching { LocalDateTime.parse(parts[0], formatter) }.getOrNull()
                ?: return@mapNotNull null
            val eTotalWh = parseDecimalKwh(parts[1], metadata.decimalPoint)
            val totalPac = parseDecimalKw(parts[2], metadata.decimalPoint)
            SpotSampleEntity(
                deviceId = deviceId,
                timestampEpochSeconds = dateTime.atZone(metadata.zoneId).toEpochSecond(),
                totalPac = totalPac,
                eTotalWh = eTotalWh,
                status = "Imported",
                sourceType = "day_csv",
            )
        }
        val grouped = spotSamples.groupBy {
            Instant.ofEpochSecond(it.timestampEpochSeconds).atZone(metadata.zoneId).toLocalDate()
        }
        val days = grouped.mapNotNull { (date, items) ->
            val ordered = items.sortedBy { it.timestampEpochSeconds }
            val first = ordered.firstOrNull()?.eTotalWh
            val last = ordered.lastOrNull()?.eTotalWh
            val dayYield = if (first != null && last != null && last > first) last - first else 0L
            val peak = items.mapNotNull { it.totalPac }.maxOrNull()
            // Constant energy (typical EToday / stalled ETotal) would store 0 and REPLACE a
            // real DayYield imported from the month CSV. Keep peak-only rows out as well.
            if (dayYield <= 0L) return@mapNotNull null
            DayAggregateEntity(
                deviceId = deviceId,
                dateEpochDay = date.toEpochDay(),
                totalYieldWh = dayYield,
                powerW = peak,
            )
        }
        return ParsedImportBundle(
            spotSamples = spotSamples,
            dayAggregates = days,
            preservedName = name,
            sourceType = ImportSourceType.FILE,
        )
    }

    private fun parseEvents(
        deviceId: Long,
        name: String,
        lines: List<String>,
        metadata: CsvMetadata,
    ): ParsedImportBundle {
        val headerIndex = lines.indexOfFirst {
            it.startsWith("DeviceType;DeviceLocation;SusyId;SerNo") ||
                it.startsWith("DeviceType,DeviceLocation,SusyId,SerNo")
        }
        if (headerIndex < 0) return ParsedImportBundle(preservedName = name, sourceType = ImportSourceType.FILE)
        val sample = lines.getOrNull(headerIndex + 1).orEmpty()
        val formatter = when {
            sample.contains('.') && sample.indexOf('.') < 3 ->
                DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss", Locale.GERMANY)
            else -> DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss", Locale.US)
        }
        val events = lines.drop(headerIndex + 1).mapNotNull { line ->
            val parts = line.split(metadata.delimiter)
            if (parts.size < 14) return@mapNotNull null
            val ts = runCatching { LocalDateTime.parse(parts[4], formatter) }.getOrNull()
                ?: return@mapNotNull null
            DeviceEventEntity(
                deviceId = deviceId,
                entryId = parts[5].toLongOrNull() ?: return@mapNotNull null,
                timestampEpochSeconds = ts.atZone(metadata.zoneId).toEpochSecond(),
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

    private fun parseDecimalKwh(value: String, decimalPoint: String): Long =
        ((parseDecimal(value, decimalPoint) ?: 0.0) * 1000.0).toLong()

    private fun parseDecimalKw(value: String, decimalPoint: String): Int =
        ((parseDecimal(value, decimalPoint) ?: 0.0) * 1000.0).toInt()

    private fun parseDecimal(value: String, decimalPoint: String): Double? {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return null
        val normalized = when {
            CsvFormat.normalizeDecimalPoint(decimalPoint) == "comma" ->
                trimmed.replace(".", "").replace(',', '.')
            else ->
                trimmed.replace(",", "")
        }
        return normalized.toDoubleOrNull()
    }

    private data class CsvMetadata(
        val decimalPoint: String,
        val delimiter: Char,
        val zoneId: ZoneId,
    )

    private data class MonthRow(
        val date: LocalDate,
        val totalYieldWh: Long,
        val dayYieldWh: Long?,
    )

    private companion object {
        private const val MAX_CSV_BYTES = 20 * 1024 * 1024
    }
}
