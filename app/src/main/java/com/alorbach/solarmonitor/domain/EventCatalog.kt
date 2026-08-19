package com.alorbach.solarmonitor.domain

import com.alorbach.solarmonitor.R
import com.alorbach.solarmonitor.data.model.DeviceEventEntity

enum class EventSeverity {
    WARNING,
    INFO,
}

object EventCatalog {
    fun severity(event: DeviceEventEntity): EventSeverity {
        val category = event.category.trim()
        val type = event.eventType.trim()
        return when {
            category.equals("Warning", ignoreCase = true) -> EventSeverity.WARNING
            category.equals("Error", ignoreCase = true) -> EventSeverity.WARNING
            category.equals("Fault", ignoreCase = true) -> EventSeverity.WARNING
            type.equals("Incoming", ignoreCase = true) -> EventSeverity.WARNING
            event.eventCode in WARNING_CODES -> EventSeverity.WARNING
            else -> EventSeverity.INFO
        }
    }

    fun categoryLabelRes(event: DeviceEventEntity): Int =
        when (severity(event)) {
            EventSeverity.WARNING -> R.string.event_cat_warning
            EventSeverity.INFO -> when {
                event.category.equals("Event", ignoreCase = true) -> R.string.event_cat_event
                else -> R.string.event_cat_info
            }
        }

    fun eventTypeLabelRes(eventType: String): Int? = when {
        eventType.equals("Incoming", ignoreCase = true) -> R.string.event_type_incoming
        eventType.equals("Outgoing", ignoreCase = true) -> R.string.event_type_outgoing
        else -> null
    }

    fun knownCodeLabelRes(eventCode: Int): Int? = when (eventCode) {
        10223 -> R.string.event_code_10223
        33 -> R.string.event_code_33
        else -> null
    }

    private val WARNING_CODES = setOf(33)
}
