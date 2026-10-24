package com.app.pixelheat.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.app.pixelheat.data.db.HardwareEventEntity
import com.app.pixelheat.data.db.ThermalSampleEntity
import com.app.pixelheat.di.AppContainer
import com.app.pixelheat.model.HeatLevel
import com.app.pixelheat.model.ThermalStatus
import com.app.pixelheat.ui.chart.ChartWindow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class HistoryPoint(val t: Long, val v: Float)

data class HistoryStats(
    val min: Float,
    val avg: Float,
    val max: Float,
    /** Share of time above the metric's notable threshold, with its label; null when not meaningful. */
    val share: Pair<String, Float>?,
)

data class HistoryData(
    val start: Long,
    val end: Long,
    val points: List<HistoryPoint>,
    val stats: HistoryStats?,
)

data class SplitTrace(
    val start: Long,
    val end: Long,
    val wifi: List<HistoryPoint>,
    val cell: List<HistoryPoint>,
)

@OptIn(ExperimentalCoroutinesApi::class)
class SensorDetailViewModel(
    private val c: AppContainer,
    val metric: Metric,
    liveSamples: Flow<List<ThermalSampleEntity>>,
) : ViewModel() {

    private val _window = MutableStateFlow(ChartWindow.H6)
    val window: StateFlow<ChartWindow> = _window.asStateFlow()

    val history: StateFlow<HistoryData?> = _window.flatMapLatest { w ->
        val from = System.currentTimeMillis() - w.durationMs
        combine(c.repository.observeSamples(from), liveSamples) { db, live ->
            val end = System.currentTimeMillis()
            val lastDb = db.lastOrNull()?.timestamp ?: 0L
            build(db + live.filter { it.timestamp > lastDb }, end - w.durationMs, end)
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Wi-Fi RSSI and cellular RSRP over the same window as [history]. Null for other sensors. */
    val signal: StateFlow<SplitTrace?> = if (metric != Metric.NETWORK) {
        MutableStateFlow(null)
    } else {
        _window.flatMapLatest { w ->
            val from = System.currentTimeMillis() - w.durationMs
            combine(c.repository.observeSamples(from), liveSamples) { db, live ->
                val end = System.currentTimeMillis()
                val start = end - w.durationMs
                val lastDb = db.lastOrNull()?.timestamp ?: 0L
                val samples = db + live.filter { it.timestamp > lastDb }
                SplitTrace(
                    start = start,
                    end = end,
                    wifi = trace(samples, start, end) { it.wifiDbm?.toFloat() },
                    cell = trace(samples, start, end) { it.cellDbm?.toFloat() },
                )
            }
        }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    }

    /** Wi-Fi and mobile throughput over the same window. Null for other sensors. */
    val traffic: StateFlow<SplitTrace?> = if (metric != Metric.NETWORK) {
        MutableStateFlow(null)
    } else {
        _window.flatMapLatest { w ->
            val from = System.currentTimeMillis() - w.durationMs
            combine(c.repository.observeSamples(from), liveSamples) { db, live ->
                val end = System.currentTimeMillis()
                val start = end - w.durationMs
                val lastDb = db.lastOrNull()?.timestamp ?: 0L
                val samples = db + live.filter { it.timestamp > lastDb }
                SplitTrace(
                    start = start,
                    end = end,
                    wifi = trace(samples, start, end) { it.wifiKBps },
                    cell = trace(samples, start, end) { it.cellKBps },
                )
            }
        }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    }

    val events: StateFlow<List<HardwareEventEntity>> =
        if (metric.eventTypes.isEmpty()) {
            flowOf(emptyList())
        } else {
            c.repository.observeEvents(System.currentTimeMillis() - EVENTS_WINDOW_MS).map { all ->
                all.filter { it.type in metric.eventTypes }.sortedByDescending { it.timestamp }.take(MAX_EVENTS)
            }
        }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setWindow(window: ChartWindow) {
        _window.value = window
    }

    /** [errorC] is how many degrees the current estimate is above the real temperature. */
    fun reportAmbientError(errorC: Float) {
        c.prefs.ambientBiasC = (c.prefs.ambientBiasC - errorC).coerceIn(-20f, 20f)
    }

    private fun build(samples: List<ThermalSampleEntity>, start: Long, end: Long): HistoryData {
        val raw = samples.filter { it.timestamp in start..end }.mapNotNull { s ->
            metric.series(s)?.takeIf { !it.isNaN() }?.let { Triple(s.timestamp, it, s.weightSec.coerceAtLeast(1f)) }
        }
        if (raw.isEmpty()) return HistoryData(start, end, emptyList(), null)

        val totalW = raw.sumOf { it.third.toDouble() }
        val avg = (raw.sumOf { it.second.toDouble() * it.third } / totalW).toFloat()
        val share = shareThreshold()?.let { (label, threshold) ->
            label to (raw.filter { it.second >= threshold }.sumOf { it.third.toDouble() } / totalW * 100).toFloat()
        }
        val stats = HistoryStats(raw.minOf { it.second }, avg, raw.maxOf { it.second }, share)
        return HistoryData(start, end, downsample(raw.map { HistoryPoint(it.first, it.second) }, start, end), stats)
    }

    private fun trace(
        samples: List<ThermalSampleEntity>,
        start: Long,
        end: Long,
        value: (ThermalSampleEntity) -> Float?,
    ): List<HistoryPoint> {
        val points = samples.mapNotNull { s ->
            if (s.timestamp !in start..end) return@mapNotNull null
            value(s)?.takeIf { !it.isNaN() }?.let { HistoryPoint(s.timestamp, it) }
        }
        return downsample(points, start, end)
    }

    /** Aggregates into time buckets; stepped kinds keep the peak so short on-states stay visible. */
    private fun downsample(points: List<HistoryPoint>, start: Long, end: Long): List<HistoryPoint> {
        if (points.size <= MAX_POINTS) return points
        val span = (end - start).coerceAtLeast(1L)
        return points.groupBy { ((it.t - start) * MAX_POINTS / span).coerceIn(0, MAX_POINTS - 1L) }
            .toSortedMap()
            .map { (_, bucket) ->
                val v = if (metric.kind.stepped) bucket.maxOf { it.v } else bucket.map { it.v }.average().toFloat()
                HistoryPoint(bucket.sumOf { it.t } / bucket.size, v)
            }
    }

    private fun shareThreshold(): Pair<String, Float>? = when (metric.kind) {
        ValueKind.TEMPERATURE -> "Time ≥ 40 °C" to 40f
        ValueKind.ON_OFF -> "Time on" to 0.5f
        ValueKind.THERMAL_STATUS -> "Time throttled" to ThermalStatus.MODERATE.toFloat()
        ValueKind.HEAT_LEVEL -> "Time in fast sampling" to HeatLevel.HOT.ordinal.toFloat()
        else -> null
    }

    private companion object {
        const val MAX_POINTS = 240
        const val MAX_EVENTS = 15
        const val EVENTS_WINDOW_MS = 7L * 24 * 60 * 60_000L
    }
}
