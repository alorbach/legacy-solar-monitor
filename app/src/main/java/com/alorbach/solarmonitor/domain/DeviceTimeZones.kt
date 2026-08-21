package com.alorbach.solarmonitor.domain

import java.time.ZoneId

/** Resolve a device timezone string; blank/invalid falls back to the system default. */
fun parseZoneId(timezone: String?): ZoneId =
    runCatching { ZoneId.of(timezone?.takeIf { it.isNotBlank() } ?: ZoneId.systemDefault().id) }
        .getOrDefault(ZoneId.systemDefault())
