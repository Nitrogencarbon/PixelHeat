package com.app.pixelheat.service

import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.app.pixelheat.data.prefs.AppPreferences
import java.util.concurrent.TimeUnit

class MonitorController(private val context: Context, private val prefs: AppPreferences) {

    fun setEnabled(enabled: Boolean) {
        prefs.setMonitoringEnabled(enabled)
        if (enabled) {
            start(fromForeground = true)
            scheduleWatchdog()
        } else {
            stop()
            WorkManager.getInstance(context).cancelUniqueWork(WATCHDOG_WORK)
        }
    }

    /**
     * Returns false when Android refuses a background FGS start; the watchdog will retry and the user
     * is asked to open the app (an allowed start point) through a notification.
     */
    fun start(fromForeground: Boolean): Boolean = try {
        ContextCompat.startForegroundService(
            context,
            Intent(context, ThermalMonitorService::class.java)
                .putExtra(ThermalMonitorService.EXTRA_FROM_FOREGROUND, fromForeground),
        )
        true
    } catch (_: ForegroundServiceStartNotAllowedException) {
        Notifications.showResumeNeeded(context)
        false
    } catch (_: IllegalStateException) {
        false
    } catch (_: SecurityException) {
        false
    }

    fun stop() {
        context.stopService(Intent(context, ThermalMonitorService::class.java))
    }

    fun scheduleWatchdog() {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WATCHDOG_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<MonitorWatchdogWorker>(30, TimeUnit.MINUTES).build(),
        )
    }

    private companion object {
        const val WATCHDOG_WORK = "thermal_watchdog"
    }
}
