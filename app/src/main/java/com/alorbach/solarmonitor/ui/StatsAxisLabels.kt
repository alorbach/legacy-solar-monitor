package com.alorbach.solarmonitor.ui

object StatsAxisLabels {
    private val hourTick = Regex("""^(\d{1,2}):00$""")

    fun tick(label: String, compactHour: Boolean): String {
        if (!compactHour) return label
        val hour = hourTick.matchEntire(label.trim())?.groupValues?.get(1) ?: return label
        return hour.padStart(2, '0')
    }

    fun step(
        count: Int,
        slotPx: Float,
        labelWidthPx: Float,
        compactHour: Boolean,
        scrolling: Boolean,
    ): Int {
        if (scrolling || count <= 1) return 1
        val needed = labelWidthPx + 10f
        if (needed > slotPx) {
            return kotlin.math.ceil(needed / slotPx).toInt().coerceIn(2, 6)
        }
        return when {
            compactHour && count > 12 -> 2
            count <= 14 -> 1
            count <= 24 -> 2
            else -> (count / 12).coerceAtLeast(2)
        }
    }
}
