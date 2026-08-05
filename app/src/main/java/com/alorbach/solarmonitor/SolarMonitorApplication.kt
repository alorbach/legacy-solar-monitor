package com.alorbach.solarmonitor

import android.app.Application
import androidx.work.Configuration
import com.alorbach.solarmonitor.data.AppContainer

class SolarMonitorApplication : Application(), Configuration.Provider {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()
}
