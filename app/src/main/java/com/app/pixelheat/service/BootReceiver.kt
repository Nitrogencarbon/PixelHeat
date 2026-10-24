package com.app.pixelheat.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.app.pixelheat.PixelHeatApp

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val c = (context.applicationContext as PixelHeatApp).container
        if (!c.prefs.monitoringEnabled.value) return
        c.monitor.start(fromForeground = false)
        c.monitor.scheduleWatchdog()
    }
}
