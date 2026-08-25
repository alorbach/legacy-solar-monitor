package com.alorbach.solarmonitor.domain

import java.util.Locale

/**
 * Pure WLAN matching rules shared by settings and automatic Bluetooth polling.
 *
 * Android may expose an SSID with surrounding quotes and uses placeholder values when the
 * connected network cannot be identified. Those values must never match a user allowlist entry.
 */
object HomeWifiPolicy {
    enum class Status {
        DISABLED,
        UNRESTRICTED,
        ALLOWED,
        NO_WIFI,
        WRONG_WIFI,
    }

    private val unavailableSsids = setOf(
        "",
        "<unknown ssid>",
        "unknown ssid",
    )

    fun normalizeSsid(value: String?): String =
        value
            ?.trim()
            ?.removeSurrounding("\"")
            ?.trim()
            ?.takeUnless { it.lowercase(Locale.ROOT) in unavailableSsids }
            .orEmpty()

    fun normalizedAllowlist(values: Iterable<String>): Set<String> =
        values.mapNotNull { normalizeSsid(it).takeIf(String::isNotEmpty) }.toSet()

    fun status(
        checkEnabled: Boolean,
        currentSsid: String?,
        allowedSsids: Iterable<String>,
    ): Status {
        if (!checkEnabled) return Status.DISABLED

        val allowlist = normalizedAllowlist(allowedSsids)
        if (allowlist.isEmpty()) return Status.UNRESTRICTED

        val current = normalizeSsid(currentSsid)
        if (current.isEmpty()) return Status.NO_WIFI
        return if (current in allowlist) Status.ALLOWED else Status.WRONG_WIFI
    }

    fun isAllowed(
        checkEnabled: Boolean,
        currentSsid: String?,
        allowedSsids: Iterable<String>,
    ): Boolean = when (status(checkEnabled, currentSsid, allowedSsids)) {
        Status.DISABLED,
        Status.UNRESTRICTED,
        Status.ALLOWED,
        -> true
        Status.NO_WIFI,
        Status.WRONG_WIFI,
        -> false
    }
}
