package com.alorbach.solarmonitor.domain

import java.util.Locale

object ReportCurrency {
    /** No conversion: an aggregate has a currency only when all tariffs agree. */
    fun commonCurrency(currencies: List<String>): String? = currencies
        .map { it.trim().uppercase(Locale.ROOT).ifEmpty { "EUR" } }
        .distinct()
        .let { if (it.isEmpty()) "EUR" else it.singleOrNull() }
}
