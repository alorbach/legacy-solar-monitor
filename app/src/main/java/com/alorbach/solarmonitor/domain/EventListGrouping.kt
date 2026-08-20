package com.alorbach.solarmonitor.domain

import com.alorbach.solarmonitor.data.model.DeviceEventEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class EventListItem(
    val key: String,
    val events: List<DeviceEventEntity>,
) {
    val representative: DeviceEventEntity get() = events.first()
    val count: Int get() = events.size
}

sealed class EventListRow {
    abstract val key: String

    data class DateHeader(val epochDay: Long) : EventListRow() {
        override val key: String = "day-$epochDay"
    }

    data class Cluster(val item: EventListItem) : EventListRow() {
        override val key: String get() = item.key
    }
}

object EventListGrouping {
    fun rows(events: List<DeviceEventEntity>, zoneId: ZoneId): List<EventListRow> {
        if (events.isEmpty()) return emptyList()
        val rows = mutableListOf<EventListRow>()
        var lastDay: Long? = null
        var cluster = mutableListOf<DeviceEventEntity>()

        fun flushCluster() {
            if (cluster.isEmpty()) return
            val first = cluster.first()
            val last = cluster.last()
            rows += EventListRow.Cluster(
                EventListItem(
                    key = "e-${first.id}-${last.id}-${cluster.size}",
                    events = cluster.toList(),
                ),
            )
            cluster = mutableListOf()
        }

        for (event in events) {
            val day = Instant.ofEpochSecond(event.timestampEpochSeconds)
                .atZone(zoneId)
                .toLocalDate()
                .toEpochDay()
            if (lastDay != day) {
                flushCluster()
                rows += EventListRow.DateHeader(day)
                lastDay = day
            }
            val current = cluster.firstOrNull()
            if (current != null && current.eventCode == event.eventCode) {
                cluster += event
            } else {
                flushCluster()
                cluster += event
            }
        }
        flushCluster()
        return rows
    }

    fun usefulOldNew(event: DeviceEventEntity): Pair<String, String>? {
        val oldValue = event.oldValue
        val newValue = event.newValue
        if (oldValue.isNullOrBlank() && newValue.isNullOrBlank()) return null
        if (oldValue == newValue) return null
        if (oldValue == "0" && newValue == "0") return null
        return (oldValue ?: "—") to (newValue ?: "—")
    }

    fun isToday(epochDay: Long, zoneId: ZoneId, today: LocalDate = LocalDate.now(zoneId)): Boolean =
        epochDay == today.toEpochDay()
}
