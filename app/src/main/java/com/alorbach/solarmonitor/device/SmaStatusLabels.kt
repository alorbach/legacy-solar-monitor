package com.alorbach.solarmonitor.device

import android.content.Context
import androidx.annotation.StringRes
import com.alorbach.solarmonitor.R

/**
 * Maps SMA Operation.Health / Operation.GriSwStt attribute IDs to localized labels.
 *
 * Live samples persist stable tokens ([encodeHealth] / [encodeRelay]) so language changes can
 * re-localize later. Imported SBFspot text and unrelated connection messages pass through.
 */
object SmaStatusLabels {
    private val healthToken = Regex("""^sma_health:(\d+)$""")
    private val relayToken = Regex("""^sma_relay:(\d+)$""")
    private val legacyHealthHex = Regex("""^Health 0x([0-9a-fA-F]+)$""")
    private val legacyRelayHex = Regex("""^Relay 0x([0-9a-fA-F]+)$""")

    fun encodeHealth(attributeId: Int): String = "sma_health:$attributeId"

    fun encodeRelay(attributeId: Int): String = "sma_relay:$attributeId"

    @StringRes
    fun healthLabelRes(attributeId: Int): Int? = when (attributeId) {
        307 -> R.string.live_health_ok
        455 -> R.string.live_health_warning
        35 -> R.string.live_health_fault
        303 -> R.string.live_health_off
        456 -> R.string.live_health_waiting_dc
        457 -> R.string.live_health_waiting_grid
        else -> null
    }

    @StringRes
    fun relayLabelRes(attributeId: Int): Int? = when (attributeId) {
        51 -> R.string.live_relay_closed
        311 -> R.string.live_relay_open
        else -> null
    }

    fun formatHealth(context: Context, attributeId: Int): String =
        healthLabelRes(attributeId)?.let(context::getString)
            ?: context.getString(R.string.live_health_unknown, hex(attributeId))

    fun formatRelay(context: Context, attributeId: Int): String =
        relayLabelRes(attributeId)?.let(context::getString)
            ?: context.getString(R.string.live_relay_unknown, hex(attributeId))

    fun displayStatus(context: Context, raw: String?): String? {
        if (raw.isNullOrBlank()) return raw
        return parseHealthId(raw)?.let { formatHealth(context, it) } ?: raw
    }

    fun displayRelay(context: Context, raw: String?): String? {
        if (raw.isNullOrBlank()) return raw
        return parseRelayId(raw)?.let { formatRelay(context, it) } ?: raw
    }

    fun parseHealthId(raw: String): Int? =
        healthToken.matchEntire(raw)?.groupValues?.get(1)?.toIntOrNull()
            ?: legacyHealthHex.matchEntire(raw)?.groupValues?.get(1)?.toIntOrNull(16)

    fun parseRelayId(raw: String): Int? =
        relayToken.matchEntire(raw)?.groupValues?.get(1)?.toIntOrNull()
            ?: legacyRelayHex.matchEntire(raw)?.groupValues?.get(1)?.toIntOrNull(16)

    fun hex(attributeId: Int): String = "0x${attributeId.toString(16)}"
}
