package com.alorbach.solarmonitor.service

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Process
import com.alorbach.solarmonitor.MainActivity
import kotlin.system.exitProcess

class RestartRelayActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mainPid = intent.getIntExtra(AppProcessRestarter.EXTRA_MAIN_PID, -1)
        if (mainPid > 0 && mainPid != Process.myPid()) {
            Process.killProcess(mainPid)
        }
        val launch = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(this, MainActivity::class.java)
        launch.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP,
        )
        startActivity(launch)
        finish()
        exitProcess(0)
    }
}
