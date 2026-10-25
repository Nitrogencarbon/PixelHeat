package com.app.pixelheat.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.app.pixelheat.PixelHeatApp
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/** Writes the measurement backup once a day while automatic export is on. */
class DataExportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as PixelHeatApp).container
        if (!c.prefs.autoExport.value) return Result.success()
        return try {
            c.archive.export()
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Gives up until tomorrow's run instead of retrying forever.
            if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
        }
    }

    private companion object {
        const val MAX_RETRIES = 3
    }
}

class ExportScheduler(private val context: Context) {
    /** [replace] restarts the daily timer. Keeping the existing one avoids delaying it on every app start. */
    fun enqueue(replace: Boolean = false) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK,
            if (replace) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP,
            // A periodic request runs immediately by default. Turning auto export on already exports once,
            // and a second back-to-back run would rotate that fresh copy into the "previous" slot.
            PeriodicWorkRequestBuilder<DataExportWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(1, TimeUnit.DAYS)
                .build(),
        )
    }

    fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(WORK)
    }

    private companion object {
        const val WORK = "measurement_export"
    }
}
