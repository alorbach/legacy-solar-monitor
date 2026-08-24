package com.alorbach.solarmonitor.service

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import com.alorbach.solarmonitor.data.settings.AppSettings
import com.alorbach.solarmonitor.domain.HomeWifiPolicy

class HomeWifiChecker(context: Context) {
    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val wifiManager =
        appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    @SuppressLint("MissingPermission", "DEPRECATION")
    fun currentSsid(): String? {
        val network = connectivityManager?.activeNetwork ?: return null
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return null
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null

        val transportInfoSsid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (capabilities.transportInfo as? WifiInfo)?.ssid
        } else {
            null
        }
        val managerSsid = runCatching { wifiManager?.connectionInfo?.ssid }.getOrNull()
        return HomeWifiPolicy.normalizeSsid(transportInfoSsid)
            .ifEmpty { HomeWifiPolicy.normalizeSsid(managerSsid) }
            .takeIf(String::isNotEmpty)
    }

    fun isAllowed(settings: AppSettings): Boolean =
        HomeWifiPolicy.isAllowed(
            checkEnabled = settings.homeWifiCheckEnabled,
            currentSsid = currentSsid(),
            allowedSsids = settings.allowedHomeWifiSsids,
        )
}
