package com.alorbach.solarmonitor.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class LivePollWindowReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != LivePollScheduler.ACTION_RESUME) return
        val appContext = context.applicationContext
        val pending = goAsync()
        scope.launch {
            try {
                LivePollScheduler.attemptResume(appContext)
            } catch (error: RuntimeException) {
                Log.w(TAG, "Live poll window resume failed", error)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "LivePollWindow"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
