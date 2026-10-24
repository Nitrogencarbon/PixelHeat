package com.app.pixelheat

import android.app.Application
import com.app.pixelheat.di.AppContainer
import com.app.pixelheat.service.Notifications

class PixelHeatApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifications.createChannels(this)
        container.privilege.init()
        if (container.prefs.monitoringEnabled.value) container.monitor.scheduleWatchdog()
    }
}
