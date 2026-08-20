package com.alorbach.solarmonitor.data.importing

import android.database.sqlite.SQLiteDatabase
import com.alorbach.solarmonitor.data.model.DayAggregateEntity
import com.alorbach.solarmonitor.data.model.DeviceEventEntity
import com.alorbach.solarmonitor.data.model.ImportSourceType
import com.alorbach.solarmonitor.data.model.MonthAggregateEntity
import com.alorbach.solarmonitor.data.model.SpotSampleEntity
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.YearMonth

class LegacySqliteImporter(
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val rowLimitExceeded: (table: String, count: Long) -> String = { table, count ->
        "SQLite import of $table has $count rows; limit is $MAX_ROWS"
    },
) {
    fun parse(deviceId: Long, dbFile: File): ParsedImportBundle {
        val db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        db.use {
            it.rawQuery("PRAGMA query_only = ON", null).close()
            it.requireRowCountAtMost("SpotData")
            val spotSamples = buildList {
                val cursor = it.rawQuery(
                    "SELECT TimeStamp,Pdc1,Pdc2,Pac1,Pac2,Pac3,EToday,ETotal,Frequency,Temperature,Status,GridRelay,BT_Signal FROM SpotData",
                    null,
                )
                cursor.use { c ->
                    while (c.moveToNext()) {
                        add(
                            SpotSampleEntity(
                                deviceId = deviceId,
                                timestampEpochSeconds = c.getLong(0),
                                pdc1 = c.getIntOrNull(1),
                                pdc2 = c.getIntOrNull(2),
                                pac1 = c.getIntOrNull(3),
                                pac2 = c.getIntOrNull(4),
                                pac3 = c.getIntOrNull(5),
                                totalPac = listOfNotNull(c.getIntOrNull(3), c.getIntOrNull(4), c.getIntOrNull(5)).sum().takeIf { sum -> sum > 0 },
                                eTodayWh = c.getLongOrNull(6),
                                eTotalWh = c.getLongOrNull(7),
                                frequencyHz = c.getDoubleOrNull(8),
                                temperatureC = c.getDoubleOrNull(9),
                                status = c.getStringOrNull(10),
                                gridRelay = c.getStringOrNull(11),
                                btSignalPercent = c.getDoubleOrNull(12),
                                sourceType = "sqlite",
                            )
                        )
                    }
                }
            }

            val dayAggregates = buildList {
                it.requireRowCountAtMost("DayData")
                val cursor = it.rawQuery("SELECT TimeStamp,TotalYield,Power FROM DayData", null)
                cursor.use { c ->
                    while (c.moveToNext()) {
                        val epochDay = Instant.ofEpochSecond(c.getLong(0))
                            .atZone(zoneId)
                            .toLocalDate()
                            .toEpochDay()
                        add(
                            DayAggregateEntity(
                                deviceId = deviceId,
                                dateEpochDay = epochDay,
                                totalYieldWh = c.getLong(1),
                                powerW = c.getIntOrNull(2),
                                sourceType = "sqlite",
                            )
                        )
                    }
                }
            }

            val monthAggregates = buildList {
                it.requireRowCountAtMost("MonthData")
                val cursor = it.rawQuery("SELECT TimeStamp,TotalYield,DayYield FROM MonthData", null)
                cursor.use { c ->
                    while (c.moveToNext()) {
                        val monthKey = Instant.ofEpochSecond(c.getLong(0))
                            .atZone(zoneId)
                            .toLocalDate()
                            .let(YearMonth::from)
                            .toString()
                        add(
                            MonthAggregateEntity(
                                deviceId = deviceId,
                                monthKey = monthKey,
                                totalYieldWh = c.getLong(1),
                                dayYieldWh = c.getLong(2),
                                sourceType = "sqlite",
                            )
                        )
                    }
                }
            }

            val events = buildList {
                it.requireRowCountAtMost("EventData")
                val cursor = it.rawQuery(
                    "SELECT EntryID,TimeStamp,EventCode,EventType,Category,EventGroup,Tag,OldValue,NewValue,UserGroup FROM EventData",
                    null,
                )
                cursor.use { c ->
                    while (c.moveToNext()) {
                        add(
                            DeviceEventEntity(
                                deviceId = deviceId,
                                entryId = c.getLong(0),
                                timestampEpochSeconds = c.getLong(1),
                                eventCode = c.getInt(2),
                                eventType = c.getString(3),
                                category = c.getString(4),
                                eventGroup = c.getString(5),
                                tag = c.getString(6),
                                oldValue = c.getStringOrNull(7),
                                newValue = c.getStringOrNull(8),
                                userGroup = c.getStringOrNull(9),
                            )
                        )
                    }
                }
            }

            return ParsedImportBundle(
                spotSamples = spotSamples,
                dayAggregates = dayAggregates,
                monthAggregates = monthAggregates,
                events = events,
                preservedName = dbFile.name,
                sourceType = ImportSourceType.SQLITE_DB,
            )
        }
    }

    private fun SQLiteDatabase.requireRowCountAtMost(table: String) {
        rawQuery("SELECT COUNT(*) FROM $table", null).use { cursor ->
            val count = if (cursor.moveToFirst()) cursor.getLong(0) else 0L
            require(count <= MAX_ROWS) { rowLimitExceeded(table, count) }
        }
    }

    companion object {
        const val MAX_ROWS = 250_000
    }

    private fun android.database.Cursor.getIntOrNull(index: Int): Int? =
        if (isNull(index)) null else getInt(index)

    private fun android.database.Cursor.getLongOrNull(index: Int): Long? =
        if (isNull(index)) null else getLong(index)

    private fun android.database.Cursor.getDoubleOrNull(index: Int): Double? =
        if (isNull(index)) null else getDouble(index)

    private fun android.database.Cursor.getStringOrNull(index: Int): String? =
        if (isNull(index)) null else getString(index)
}
