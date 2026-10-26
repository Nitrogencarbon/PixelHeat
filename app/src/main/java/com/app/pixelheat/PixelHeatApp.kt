package com.app.pixelheat

import android.app.Application
import com.app.pixelheat.di.AppContainer
import com.app.pixelheat.service.Notifications
import rikka.sui.Sui

class PixelHeatApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // Sui is optional. On a release build the hidden lookup throws; Shizuku still connects on its own.
        runCatching { if (!Sui.isSui()) Sui.init(packageName) }
        container = AppContainer(this)
        Notifications.createChannels(this)
        container.privilege.init()
        if (container.prefs.monitoringEnabled.value) container.monitor.scheduleWatchdog()
    }
}
