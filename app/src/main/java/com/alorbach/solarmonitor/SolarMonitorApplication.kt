package com.alorbach.solarmonitor

import android.app.Application
import androidx.work.Configuration
import com.alorbach.solarmonitor.data.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SolarMonitorApplication : Application(), Configuration.Provider {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { container.settingsStore.migrateLegacySecrets() }
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
