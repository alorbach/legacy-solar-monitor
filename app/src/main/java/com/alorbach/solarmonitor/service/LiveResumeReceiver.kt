package com.alorbach.solarmonitor.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Handles the user action from the inexact-alarm resume notification. */
class LiveResumeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_RESUME) return
        val appContext = context.applicationContext
        val pending = goAsync()
        scope.launch {
            try {
                LivePollScheduler.attemptResume(
                    appContext,
                    LivePollScheduler.FgsStartPolicy.ALLOW_BACKGROUND_START,
                )
            } catch (error: RuntimeException) {
                Log.w(TAG, "Live monitor did not resume from notification", error)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_RESUME = "com.alorbach.solarmonitor.action.RESUME_LIVE_FROM_NOTIFICATION"
        private const val TAG = "LiveResumeReceiver"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
