package com.alorbach.solarmonitor.service

import android.content.Context
import android.content.Intent
import android.os.Process

/**
 * Restarts after Room's database file was replaced, via [RestartRelayActivity]
 * in a second process so the main process is not killed with [kotlin.system.exitProcess]
 * while the UI is still visible.
 */
object AppProcessRestarter {
    const val EXTRA_MAIN_PID = "com.alorbach.solarmonitor.restart.MAIN_PID"
    const val RELAY_PROCESS_SUFFIX = ":restart"

    fun restart(context: Context) {
        val app = context.applicationContext
        app.startActivity(relayIntent(app, Process.myPid()))
    }

    fun isRelayProcessName(processName: String): Boolean =
        processName.endsWith(RELAY_PROCESS_SUFFIX)

    fun relayIntent(context: Context, mainPid: Int): Intent =
        Intent(context, RestartRelayActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(EXTRA_MAIN_PID, mainPid)
        }
}
