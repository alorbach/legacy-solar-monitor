package com.alorbach.solarmonitor.domain

import java.util.Locale

/**
 * Pure WLAN matching rules shared by settings and automatic Bluetooth polling.
 *
 * Android may expose an SSID with surrounding quotes and uses placeholder values when the
 * connected network cannot be identified. Those values must never match a user allowlist entry.
 */
object HomeWifiPolicy {
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

    fun isAllowed(
        checkEnabled: Boolean,
        currentSsid: String?,
        allowedSsids: Iterable<String>,
    ): Boolean {
        if (!checkEnabled) return true
        // Empty allowlist = not configured yet: allow any network so updates do not pause
        // automatic monitoring until the user adds specific home SSIDs.
        val allowlist = normalizedAllowlist(allowedSsids)
        if (allowlist.isEmpty()) return true
        val current = normalizeSsid(currentSsid)
        return current.isNotEmpty() && current in allowlist
    }
}
