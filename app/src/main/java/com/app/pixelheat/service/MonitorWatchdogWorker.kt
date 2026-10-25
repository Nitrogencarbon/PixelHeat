package com.app.pixelheat.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.app.pixelheat.PixelHeatApp
import com.app.pixelheat.model.DetailLevel
import com.app.pixelheat.model.HeatLevel

/**
 * Safety net: folds raw samples older than 30 days into hourly averages and, if the system killed
 * the monitor, records a fallback sample so history has no holes, then tries to restart the service.
 */
class MonitorWatchdogWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val c = (applicationContext as PixelHeatApp).container
        val now = System.currentTimeMillis()
        c.repository.compactHistory(now)
        if (!c.prefs.monitoringEnabled.value || ThermalMonitorService.isRunning) return Result.success()

        runCatching { c.repository.record(c.collector.collect(DetailLevel.BASIC), HeatLevel.COLD) }
        c.monitor.start(fromForeground = false)
        return Result.success()
    }
}
