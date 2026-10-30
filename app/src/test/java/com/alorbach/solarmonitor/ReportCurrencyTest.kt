package com.alorbach.solarmonitor

import com.alorbach.solarmonitor.domain.ReportCurrency
import com.alorbach.solarmonitor.domain.YieldFormatting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReportCurrencyTest {
    @Test
    fun sameCurrencyCanBeAggregatedAfterNormalization() {
        assertEquals("EUR", ReportCurrency.commonCurrency(listOf("EUR", " eur ")))
        assertEquals("USD", ReportCurrency.commonCurrency(listOf("USD", "USD")))
    }

    @Test
    fun missingTariffsKeepTheDefaultCurrency() {
        assertEquals("EUR", ReportCurrency.commonCurrency(emptyList()))
    }

    @Test
    fun differentCurrenciesHaveNoAggregateCurrencyOrFormattedAmount() {
        val currency = ReportCurrency.commonCurrency(listOf("EUR", "USD"))
        assertNull(currency)
        assertEquals("—", YieldFormatting.earningsLabel(123.0, currency))
        assertEquals("—", YieldFormatting.earningsLabel(null, "EUR"))
    }
}
