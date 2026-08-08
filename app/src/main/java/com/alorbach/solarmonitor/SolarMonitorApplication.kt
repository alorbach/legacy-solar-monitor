package com.alorbach.solarmonitor

import android.app.Application
import android.content.Context
import androidx.work.Configuration
import com.alorbach.solarmonitor.data.AppContainer
import com.alorbach.solarmonitor.i18n.LocaleController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SolarMonitorApplication : Application(), Configuration.Provider {
    lateinit var container: AppContainer
        private set

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleController.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { container.settingsStore.migrateLegacySecrets() }
            runCatching { container.repository.backfillHourAggregatesIfNeeded() }
            runCatching {
                val tag = container.settingsStore.settings.first().languageTag
                LocaleController.apply(this@SolarMonitorApplication, tag)
            }
        }
    }

    override fun onTerminate() {
        if (::container.isInitialized) {
            container.close()
        }
        super.onTerminate()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()
}
