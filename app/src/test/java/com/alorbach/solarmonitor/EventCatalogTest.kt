package com.alorbach.solarmonitor

import com.alorbach.solarmonitor.R
import com.alorbach.solarmonitor.data.model.DeviceEventEntity
import com.alorbach.solarmonitor.data.model.StatsPoint
import com.alorbach.solarmonitor.domain.EventCatalog
import com.alorbach.solarmonitor.domain.EventSeverity
import com.alorbach.solarmonitor.domain.YieldFormatting
import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EventCatalogTest {
    @Test
    fun warningCategoryAndIncomingTypeAreWarnings() {
        assertEquals(EventSeverity.WARNING, EventCatalog.severity(event(category = "Warning")))
        assertEquals(EventSeverity.WARNING, EventCatalog.severity(event(category = "Event", type = "Incoming")))
        assertEquals(EventSeverity.WARNING, EventCatalog.severity(event(category = "Event", type = "Outgoing", code = 33)))
        assertEquals(EventSeverity.INFO, EventCatalog.severity(event(category = "Event", type = "Outgoing", code = 10223)))
    }

    @Test
    fun labelsUseResourcesNotEnglishLiterals() {
        val warning = event(category = "Warning", type = "Incoming", code = 33)
        assertEquals(R.string.event_cat_warning, EventCatalog.categoryLabelRes(warning))
        assertEquals(R.string.event_type_incoming, EventCatalog.eventTypeLabelRes(warning.eventType))
        assertEquals(R.string.event_code_33, EventCatalog.knownCodeLabelRes(33))
        assertEquals(R.string.event_code_10223, EventCatalog.knownCodeLabelRes(10223))
        assertEquals(null, EventCatalog.knownCodeLabelRes(1))
        assertEquals(R.string.event_type_outgoing, EventCatalog.eventTypeLabelRes("Outgoing"))
    }

    private fun event(
        category: String,
        type: String = "Outgoing",
        code: Int = 10223,
    ) = DeviceEventEntity(
        id = 1,
        deviceId = 1,
        entryId = 9,
        timestampEpochSeconds = 1_700_000_000,
        eventCode = code,
        eventType = type,
        category = category,
        eventGroup = "Grid",
        tag = "raw-tag",
        oldValue = "0",
        newValue = "1",
        userGroup = null,
    )
}

class StatsPointEventCountTest {
    @Test
    fun eventCountDefaultsToZero() {
        val point = StatsPoint(
            label = "01",
            bucketKey = "1",
            yieldWh = 1000,
            peakPowerW = null,
            earnings = 0.0,
        )
        assertEquals(0, point.eventCount)
    }
}

class CompactKwhNumberTest {
    @Test
    fun germanLocaleUsesCommaDecimals() {
        val locale = Locale.GERMANY
        assertEquals("0,00", YieldFormatting.compactKwhNumber(0, locale))
        assertEquals("0,85", YieldFormatting.compactKwhNumber(850, locale))
        assertEquals("12,5", YieldFormatting.compactKwhNumber(12_500, locale))
        assertEquals("150", YieldFormatting.compactKwhNumber(150_400, locale))
    }
}

class ImportStringFormatTest {
    @Test
    fun germanImportResultFormatsCounts() {
        val template = loadAppString("values-de", "import_result")
        val formatted = String.format(Locale.GERMANY, template, 7628, 10, 20, 3, 12_584)
        assertEquals(
            "Importiert 7628 Datei(en): 10 Spot-Werte, 20 Tage, 3 Monate, 12584 Ereignisse",
            formatted,
        )
    }

    @Test
    fun englishImportResultKeepsPlaceholders() {
        val template = loadAppString("values", "import_result")
        val formatted = String.format(Locale.US, template, 2, 4, 6, 8, 10)
        assertTrue(formatted.contains("2"))
        assertTrue(formatted.contains("10"))
        assertTrue(formatted.startsWith("Imported"))
    }

    private fun loadAppString(valuesFolder: String, name: String): String {
        val file = File("src/main/res/$valuesFolder/strings.xml")
        val match = Regex("""<string name="$name">([^<]+)</string>""").find(file.readText())
        requireNotNull(match) { "Missing $name in ${file.absolutePath}" }
        return match.groupValues[1]
            .replace("\\'", "'")
            .replace("&amp;", "&")
    }
}
