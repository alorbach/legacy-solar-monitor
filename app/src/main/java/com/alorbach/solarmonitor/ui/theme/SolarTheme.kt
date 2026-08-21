package com.alorbach.solarmonitor.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.alorbach.solarmonitor.data.settings.ChartBarAccent

/** Brand tokens from the meter-sun launcher mark. */
object SolarPalette {
    val Navy = Color(0xFF06161A)
    val NavyDeep = Color(0xFF080D1A)
    val SurfaceDark = Color(0xFF0F1A22)
    val SurfaceVariantDark = Color(0xFF1A2832)
    val Ink = Color(0xFF17212B)
    val Gold = Color(0xFFFFC52F)
    val GoldDim = Color(0xFFF4B400)
    val Cyan = Color(0xFF17D7D1)
    val CyanContainerLight = Color(0xFFC8F6F3)
    val CyanOnContainerLight = Color(0xFF043B39)
    val CyanContainerDark = Color(0xFF0B4A47)
    val CyanOnContainerDark = Color(0xFFC8F6F3)
    val Cream = Color(0xFFF4F0E8)
    val CreamSurface = Color(0xFFF8F5EE)
    val CreamVariant = Color(0xFFE4DED5)
    val OnVariantLight = Color(0xFF5C636B)
    val OnVariantDark = Color(0xFFB8BFC7)
    val ErrorLight = Color(0xFF8E2A2A)
    val OnErrorLight = Color(0xFFFFF8F6)
    val ErrorContainerLight = Color(0xFFF5D6D6)
    val OnErrorContainerLight = Color(0xFF5C1414)
    val ErrorDark = Color(0xFFE08A8A)
    val OnErrorDark = Color(0xFF3A1010)
    val ErrorContainerDark = Color(0xFF5C1414)
    val OnErrorContainerDark = Color(0xFFF5D6D6)
}

/** Resolved colors for yield bar charts / widget hour bars. */
data class ChartBarColors(
    val bar: Color,
    val selected: Color,
    val onBar: Color,
)

fun chartBarColors(accent: ChartBarAccent): ChartBarColors =
    when (accent) {
        ChartBarAccent.GOLD -> ChartBarColors(
            bar = SolarPalette.Gold,
            selected = SolarPalette.GoldDim,
            onBar = SolarPalette.Ink,
        )
        ChartBarAccent.CYAN -> ChartBarColors(
            bar = SolarPalette.Cyan,
            selected = SolarPalette.Gold,
            onBar = SolarPalette.Ink,
        )
    }

val SolarLightColors = lightColorScheme(
    primary = SolarPalette.Ink,
    onPrimary = SolarPalette.Gold,
    secondary = SolarPalette.Gold,
    onSecondary = SolarPalette.Ink,
    tertiary = SolarPalette.Cyan,
    onTertiary = SolarPalette.Ink,
    tertiaryContainer = SolarPalette.CyanContainerLight,
    onTertiaryContainer = SolarPalette.CyanOnContainerLight,
    background = SolarPalette.Cream,
    surface = SolarPalette.CreamSurface,
    surfaceVariant = SolarPalette.CreamVariant,
    onBackground = SolarPalette.Ink,
    onSurface = SolarPalette.Ink,
    onSurfaceVariant = SolarPalette.OnVariantLight,
    error = SolarPalette.ErrorLight,
    onError = SolarPalette.OnErrorLight,
    errorContainer = SolarPalette.ErrorContainerLight,
    onErrorContainer = SolarPalette.OnErrorContainerLight,
)

val SolarDarkColors = darkColorScheme(
    primary = SolarPalette.Gold,
    onPrimary = SolarPalette.Navy,
    secondary = SolarPalette.GoldDim,
    onSecondary = SolarPalette.Navy,
    tertiary = SolarPalette.Cyan,
    onTertiary = SolarPalette.Navy,
    tertiaryContainer = SolarPalette.CyanContainerDark,
    onTertiaryContainer = SolarPalette.CyanOnContainerDark,
    background = SolarPalette.NavyDeep,
    surface = SolarPalette.SurfaceDark,
    surfaceVariant = SolarPalette.SurfaceVariantDark,
    onBackground = SolarPalette.Cream,
    onSurface = SolarPalette.Cream,
    onSurfaceVariant = SolarPalette.OnVariantDark,
    error = SolarPalette.ErrorDark,
    onError = SolarPalette.OnErrorDark,
    errorContainer = SolarPalette.ErrorContainerDark,
    onErrorContainer = SolarPalette.OnErrorContainerDark,
)
