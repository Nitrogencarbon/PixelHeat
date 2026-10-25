package com.app.pixelheat.service

import android.Manifest
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.app.pixelheat.PixelHeatApp
import com.app.pixelheat.di.AppContainer
import com.app.pixelheat.diagnostics.DiagnosisStatus
import com.app.pixelheat.model.DetailLevel
import com.app.pixelheat.model.HeatLevel
import com.app.pixelheat.model.MonitorStatus
import com.app.pixelheat.model.ThermalSnapshot
import com.app.pixelheat.model.ThermalStatus
import com.app.pixelheat.util.formatTemp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Passive, adaptive thermal monitor.
 *
 * It holds no wakelock: between samples it suspends on a timer *or* on a hardware trigger
 * (screen, charger, GNSS, camera, thermal status, battery temperature step). When the device
 * is cold and dozing the timer simply waits for the next maintenance window, so the cost is
 * near zero; when hot or fast charging the CPU is awake anyway and sampling becomes dense.
 */
class ThermalMonitorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val wakeups = Channel<Unit>(Channel.CONFLATED)
    private val policy = AdaptiveSamplingPolicy()
    private lateinit var container: AppContainer
    private var loopJob: Job? = null

    /** Whether the running foreground service holds the location type; passive location needs it. */
    @Volatile
    private var locationAllowed = false

    override fun onCreate() {
        super.onCreate()
        container = (application as PixelHeatApp).container
        isRunning = true
        container.tracker.acquire()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val fromForeground = intent?.getBooleanExtra(EXTRA_FROM_FOREGROUND, false) == true
        if (!promote(fromForeground)) {
            // startForegroundService() obliges the service to call startForeground() before it stops,
            // otherwise Android raises ForegroundServiceDidNotStartInTimeException.
            runCatching {
                ServiceCompat.startForeground(
                    this,
                    Notifications.ID_MONITOR,
                    Notifications.monitor(this, "Pixel Heat", "Monitoring could not start."),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            }
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            Notifications.showResumeNeeded(this)
            stopSelf()
            return START_NOT_STICKY
        }
        Notifications.clearResumeNeeded(this)
        if (loopJob == null) {
            loopJob = scope.launch { runLoop() }
            scope.launch { container.tracker.triggers.collect { wakeups.trySend(Unit) } }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        container.tracker.release()
        container.passiveLocation.stop()
        isRunning = false
        _status.value = null
        super.onDestroy()
    }

    private fun promote(fromForeground: Boolean): Boolean {
        val notification = Notifications.monitor(this, "Pixel Heat", "Starting thermal monitoring…")
        Notifications.forgetScore()
        val locationGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val special = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        val withLocation = special or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        // A restart by Android or the watchdog has no foreground origin, so it cannot hold the location type.
        // Keep the location type from an earlier start in that case instead of dropping it.
        val wantLocation = locationGranted && (fromForeground || locationAllowed)
        return try {
            val types = if (wantLocation) withLocation else special
            ServiceCompat.startForeground(this, Notifications.ID_MONITOR, notification, types)
            locationAllowed = wantLocation
            true
        } catch (_: SecurityException) {
            locationAllowed = false
            runCatching { ServiceCompat.startForeground(this, Notifications.ID_MONITOR, notification, special) }.isSuccess
        } catch (_: IllegalStateException) {
            false
        }
    }

    private suspend fun runLoop() {
        var lastSampleAt = 0L
        while (scope.isActive) {
            val level = policy.level
            val snapshot = try {
                container.collector.collect(level.detail)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }

            var interval = level.intervalMs
            if (snapshot != null) {
                lastSampleAt = snapshot.timestamp
                // A failed write (disk full, locked database) skips this sample instead of killing the monitor.
                swallow { container.repository.record(snapshot, level) }
                swallow {
                    container.repository.recordAppUsage(
                        snapshot.timestamp,
                        container.appActivity.read(snapshot.timestamp, snapshot.gpsActive, snapshot.cameraActive),
                    )
                }
                val next = policy.evaluate(snapshot)
                if (next.detail == DetailLevel.FULL && locationAllowed) {
                    container.passiveLocation.start()
                } else {
                    container.passiveLocation.stop()
                }
                interval = next.intervalMs
                _status.value = MonitorStatus(
                    level = next,
                    lastSampleAt = snapshot.timestamp,
                    nextSampleAt = snapshot.timestamp + interval,
                    referenceTemp = snapshot.referenceTemp,
                    referenceSource = snapshot.referenceSource,
                    thermalStatus = snapshot.thermalStatus,
                )
                Notifications.showScore(
                    this,
                    snapshot.referenceTemp,
                    snapshot.referenceSource,
                    snapshot.thermalStatus,
                    interval,
                )
                swallow { maybeAlert(snapshot) }
            }

            withTimeoutOrNull(interval) { wakeups.receive() }
            val sinceLast = System.currentTimeMillis() - lastSampleAt
            if (sinceLast < MIN_TRIGGER_GAP_MS) delay(MIN_TRIGGER_GAP_MS - sinceLast)
        }
    }

    private inline fun swallow(block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private suspend fun maybeAlert(s: ThermalSnapshot) {
        val severe = s.thermalStatus >= ThermalStatus.SEVERE
        if (!severe) return
        val now = System.currentTimeMillis()
        if (now - container.prefs.lastAlertAt < ALERT_COOLDOWN_MS) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return

        val from = now - ALERT_WINDOW_MS
        val report = container.analyzer.analyze(
            container.repository.samplesBetween(from, now),
            container.repository.eventsBetween(from, now),
            container.repository.appUsageBetween(from, now),
        )
        if (report.status !in ALERT_STATUSES) return
        container.prefs.lastAlertAt = now
        runCatching {
            NotificationManagerCompat.from(this).notify(
                Notifications.ID_ALERT,
                Notifications.alert(this, "${formatTemp(s.referenceTemp)} · ${report.headline}", report.explanation),
            )
        }
    }

    companion object {
        const val EXTRA_FROM_FOREGROUND = "from_foreground"
        private const val MIN_TRIGGER_GAP_MS = 10_000L
        private const val ALERT_COOLDOWN_MS = 20 * 60_000L
        private const val ALERT_WINDOW_MS = 30 * 60_000L
        private val ALERT_STATUSES = setOf(DiagnosisStatus.HEATING, DiagnosisStatus.HOT, DiagnosisStatus.CRITICAL)

        @Volatile
        var isRunning = false
            private set

        private val _status = MutableStateFlow<MonitorStatus?>(null)
        val status: StateFlow<MonitorStatus?> = _status.asStateFlow()
    }
}
