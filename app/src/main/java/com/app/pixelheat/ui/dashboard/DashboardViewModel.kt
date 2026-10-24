package com.app.pixelheat.ui.dashboard

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.app.pixelheat.PixelHeatApp
import com.app.pixelheat.data.db.ThermalSampleEntity
import com.app.pixelheat.data.toEntity
import com.app.pixelheat.diagnostics.DiagnosisReport
import com.app.pixelheat.diagnostics.HeatEpisode
import com.app.pixelheat.model.DetailLevel
import com.app.pixelheat.model.DeviceIdentity
import com.app.pixelheat.model.HeatLevel
import com.app.pixelheat.model.LifetimeAverages
import com.app.pixelheat.model.MonitorStatus
import com.app.pixelheat.model.PrivilegeState
import com.app.pixelheat.model.ThermalSnapshot
import com.app.pixelheat.service.Notifications
import com.app.pixelheat.service.ThermalMonitorService
import com.app.pixelheat.ui.chart.ChartBuilder
import com.app.pixelheat.ui.chart.ChartData
import com.app.pixelheat.ui.chart.ChartWindow
import com.app.pixelheat.ui.chart.SeriesKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModel(application: Application) : AndroidViewModel(application) {
    private val c = (application as PixelHeatApp).container

    val identity: DeviceIdentity = c.deviceInfo.identity()
    val privilege: StateFlow<PrivilegeState> = c.privilege.state
    val monitorStatus: StateFlow<MonitorStatus?> = ThermalMonitorService.status
    private val _window = MutableStateFlow(ChartWindow.H6)
    val window: StateFlow<ChartWindow> = _window.asStateFlow()
    private val _series = MutableStateFlow(loadChartSeries())

    /** Live samples taken while the dashboard is visible; never persisted, merged into analyses. */
    private val liveBuffer = MutableStateFlow<List<ThermalSampleEntity>>(emptyList())
    val liveSamples: StateFlow<List<ThermalSampleEntity>> = liveBuffer.asStateFlow()

    val live: StateFlow<ThermalSnapshot?> = flow {
        c.tracker.acquire()
        try {
            var tick = 0
            while (true) {
                val detail = if (tick % FULL_EVERY_TICKS == 0) DetailLevel.FULL else DetailLevel.BASIC
                val snapshot = try {
                    c.collector.collect(detail)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                if (snapshot != null) {
                    appendLive(snapshot)
                    publishScore(snapshot)
                    emit(snapshot)
                }
                tick++
                delay(LIVE_INTERVAL_MS)
            }
        } finally {
            c.tracker.release()
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val lifetime: StateFlow<LifetimeAverages?> = c.repository.observeLifetime(identity.installedAt)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val chart: StateFlow<ChartData?> = combine(_window, _series) { window, series -> window to series }
        .flatMapLatest { (w, series) ->
            ticker(REQUERY_MS).flatMapLatest {
                val from = System.currentTimeMillis() - w.durationMs
                combine(c.repository.observeSamples(from), c.repository.observeEvents(from), liveBuffer) { s, e, l ->
                    val merged = merge(s, l)
                    ChartBuilder.build(
                        merged,
                        e,
                        w,
                        System.currentTimeMillis(),
                        { ev -> c.analyzer.eventImpact(merged, ev) },
                        series,
                    )
                }
            }
        }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val diagnosis: StateFlow<DiagnosisReport?> = ticker(REQUERY_MS).flatMapLatest {
        val from = System.currentTimeMillis() - DIAGNOSIS_WINDOW_MS
        combine(
            c.repository.observeSamples(from),
            c.repository.observeEvents(from),
            c.repository.observeAppUsage(from),
            liveBuffer,
        ) { s, e, apps, l ->
            val cutoff = System.currentTimeMillis() - DIAGNOSIS_WINDOW_MS
            c.analyzer.analyze(merge(s, l).filter { it.timestamp >= cutoff }, e, apps)
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val episodes: StateFlow<List<HeatEpisode>> = ticker(EPISODES_REFRESH_MS).map {
        val now = System.currentTimeMillis()
        val from = now - EPISODES_WINDOW_MS
        c.analyzer.findEpisodes(
            c.repository.samplesBetween(from, now),
            c.repository.eventsBetween(from, now),
            c.repository.appUsageBetween(from, now),
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val onboardingDone: Boolean get() = c.prefs.onboardingDone

    fun markOnboarded() {
        c.prefs.onboardingDone = true
    }

    fun setWindow(window: ChartWindow) {
        _window.value = window
    }

    fun toggleSeries(key: SeriesKey) {
        _series.update { stored ->
            val current = stored ?: chart.value?.visible ?: emptySet()
            val next = if (key in current) current - key else current + key
            c.prefs.setChartSeriesNames(next.map { it.name }.toSet())
            next
        }
    }

    /** Location may have been granted: re-register GNSS and re-promote the service with the location type. */
    fun onPermissionsChanged() {
        c.tracker.refreshPermissions()
        if (c.prefs.monitoringEnabled.value) c.monitor.start(fromForeground = true)
    }

    fun ensureMonitoring() {
        if (c.prefs.monitoringEnabled.value) {
            c.monitor.start(fromForeground = true)
            c.monitor.scheduleWatchdog()
        }
    }

    private fun publishScore(snapshot: ThermalSnapshot) {
        if (!ThermalMonitorService.isRunning) return
        val interval = monitorStatus.value?.level?.intervalMs ?: HeatLevel.IDLE.intervalMs
        Notifications.showScore(
            getApplication(),
            snapshot.referenceTemp,
            snapshot.referenceSource,
            snapshot.thermalStatus,
            interval,
        )
    }

    private fun loadChartSeries(): Set<SeriesKey>? {
        val stored = c.prefs.chartSeriesNames() ?: return null
        val keys = stored.mapNotNull { name -> SeriesKey.entries.firstOrNull { it.name == name } }.toSet()
        return keys.takeIf { it.isNotEmpty() || stored.isEmpty() }
    }

    private fun appendLive(snapshot: ThermalSnapshot) {
        liveBuffer.update { current ->
            val lastAt = current.lastOrNull()?.timestamp ?: 0L
            if (snapshot.timestamp - lastAt < LIVE_BUFFER_STEP_MS) return@update current
            val level = monitorStatus.value?.level ?: HeatLevel.COLD
            val cutoff = snapshot.timestamp - LIVE_BUFFER_SPAN_MS
            (current + snapshot.toEntity(level)).filter { it.timestamp >= cutoff }
        }
    }

    private fun merge(db: List<ThermalSampleEntity>, live: List<ThermalSampleEntity>): List<ThermalSampleEntity> {
        val lastDb = db.lastOrNull()?.timestamp ?: 0L
        return db + live.filter { it.timestamp > lastDb }
    }

    private fun ticker(periodMs: Long): Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(periodMs)
        }
    }

    private companion object {
        const val LIVE_INTERVAL_MS = 2_000L
        const val FULL_EVERY_TICKS = 5
        const val LIVE_BUFFER_STEP_MS = 15_000L
        const val LIVE_BUFFER_SPAN_MS = 60 * 60_000L
        const val REQUERY_MS = 60_000L
        const val DIAGNOSIS_WINDOW_MS = 45 * 60_000L
        const val EPISODES_REFRESH_MS = 5 * 60_000L
        const val EPISODES_WINDOW_MS = 7L * 24 * 60 * 60_000L
    }
}
