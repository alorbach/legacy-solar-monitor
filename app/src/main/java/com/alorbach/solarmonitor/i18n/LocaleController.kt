package com.alorbach.solarmonitor.i18n

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * Application language helper.
 *
 * - Empty tag = follow the system locale (auto-detect).
 * - On API 33+ uses [LocaleManager].
 * - Below API 33 stores the tag in SharedPreferences and wraps contexts via
 *   [createConfigurationContext] from [attachBaseContext].
 */
object LocaleController {
    private const val PREFS = "locale_prefs"
    private const val KEY_TAG = "language_tag"

    /**
     * Prefer [Context.getApplicationContext] when available. During
     * [android.app.Application.attachBaseContext] it is still null, so fall back to [context].
     */
    private fun prefsContext(context: Context): Context =
        context.applicationContext ?: context

    fun storedTag(context: Context): String =
        prefsContext(context)
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TAG, "")
            .orEmpty()

    fun wrap(context: Context): Context {
        val tag = storedTag(context)
        if (tag.isBlank() || Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return context
        }
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        config.setLocales(LocaleList(locale))
        return context.createConfigurationContext(config)
    }

    fun apply(context: Context, languageTag: String) {
        val app = prefsContext(context)
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TAG, languageTag)
            .apply()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val localeManager = app.getSystemService(LocaleManager::class.java)
            localeManager.applicationLocales = if (languageTag.isBlank()) {
                LocaleList.getEmptyLocaleList()
            } else {
                LocaleList.forLanguageTags(languageTag)
            }
        } else if (languageTag.isNotBlank()) {
            Locale.setDefault(Locale.forLanguageTag(languageTag))
        }
    }
}
