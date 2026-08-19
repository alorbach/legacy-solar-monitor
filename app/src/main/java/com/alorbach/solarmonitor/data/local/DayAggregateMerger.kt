package com.alorbach.solarmonitor.data.local

import com.alorbach.solarmonitor.data.model.DayAggregateEntity

object DayAggregateMerger {
    const val SOURCE_MONTH_CSV = "month_csv"

    fun coalesce(items: List<DayAggregateEntity>): List<DayAggregateEntity> {
        if (items.size <= 1) return items
        val folded = LinkedHashMap<Pair<Long, Long>, DayAggregateEntity>()
        for (item in items) {
            val key = item.deviceId to item.dateEpochDay
            val prior = folded[key]
            folded[key] = if (prior == null) item else merge(prior, item)
        }
        return folded.values.toList()
    }

    fun merge(prior: DayAggregateEntity, incoming: DayAggregateEntity): DayAggregateEntity {
        val picked = pickYield(prior, incoming)
        val powerW = if (picked.incomingWins) {
            incoming.powerW ?: prior.powerW
        } else {
            prior.powerW ?: incoming.powerW
        }
        return prior.copy(
            totalYieldWh = picked.yieldWh,
            powerW = powerW,
            sourceType = picked.sourceType,
        )
    }

    internal fun sourceRank(sourceType: String): Int = when (sourceType) {
        "bluetooth_day_archive", "bluetooth_month_archive", "sqlite" -> 3
        SOURCE_MONTH_CSV -> 2
        else -> 1
    }

    private fun isBluetoothArchive(sourceType: String): Boolean =
        sourceType == "bluetooth_day_archive" || sourceType == "bluetooth_month_archive"

    private data class PickedYield(
        val yieldWh: Long,
        val sourceType: String,
        val incomingWins: Boolean,
    )

    private fun pickYield(
        prior: DayAggregateEntity,
        incoming: DayAggregateEntity,
    ): PickedYield {
        val incomingRank = sourceRank(incoming.sourceType)
        val priorRank = sourceRank(prior.sourceType)
        return when {
            incomingRank > priorRank ->
                if (isBluetoothArchive(incoming.sourceType) &&
                    incoming.totalYieldWh <= 0L &&
                    prior.totalYieldWh > 0L
                ) {
                    PickedYield(prior.totalYieldWh, prior.sourceType, incomingWins = false)
                } else {
                    PickedYield(incoming.totalYieldWh, incoming.sourceType, incomingWins = true)
                }
            incomingRank < priorRank ->
                PickedYield(prior.totalYieldWh, prior.sourceType, incomingWins = false)
            incoming.totalYieldWh <= 0L && incomingRank < 2 ->
                PickedYield(prior.totalYieldWh, prior.sourceType, incomingWins = false)
            else ->
                PickedYield(incoming.totalYieldWh, incoming.sourceType, incomingWins = true)
        }
    }
}
