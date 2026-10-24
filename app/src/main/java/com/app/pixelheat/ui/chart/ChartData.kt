package com.app.pixelheat.ui.chart

import androidx.compose.ui.graphics.Color
import com.app.pixelheat.data.db.HardwareEventEntity
import com.app.pixelheat.data.db.describe
import com.app.pixelheat.data.db.ThermalSampleEntity
import com.app.pixelheat.diagnostics.EventImpact
import com.app.pixelheat.model.HardwareEventType
import com.app.pixelheat.ui.theme.SeriesBattery
import com.app.pixelheat.ui.theme.SeriesCpu
import com.app.pixelheat.ui.theme.SeriesGpu
import com.app.pixelheat.ui.theme.SeriesSkin
import com.app.pixelheat.util.formatSignedTemp
import com.app.pixelheat.util.formatTime
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

enum class ChartWindow(val label: String, val durationMs: Long, val bars: Int) {
    H1("1 h", 60 * 60_000L, 12),
    H6("6 h", 6 * 60 * 60_000L, 24),
    H24("24 h", 24 * 60 * 60_000L, 24),
    D7("7 d", 7 * 24 * 60 * 60_000L, 28),
}

enum class SeriesKey(val label: String, val color: Color, val selector: (ThermalSampleEntity) -> Float?) {
    BATTERY("Battery", SeriesBattery, { it.batteryTemp }),
    SKIN("Skin", SeriesSkin, { it.skinTemp }),
    CPU("CPU", SeriesCpu, { it.cpuTemp }),
    GPU("GPU", SeriesGpu, { it.gpuTemp }),
}

data class ChartPoint(val t: Long, val v: Float)
data class ChartSeries(val key: SeriesKey, val points: List<ChartPoint>)
data class AmbientBar(val start: Long, val end: Long, val value: Float)

data class ChartMarker(
    val t: Long,
    val type: HardwareEventType,
    val label: String,
    val impact: EventImpact?,
) {
    val significant: Boolean get() = impact?.significant == true
}

data class ChartData(
    val start: Long,
    val end: Long,
    val series: List<ChartSeries>,
    val bars: List<AmbientBar>,
    val markers: List<ChartMarker>,
    val minY: Float,
    val maxY: Float,
    val gapMs: Long,
    val window: ChartWindow,
    val hasSamples: Boolean,
    /** Series that have at least one reading in the window, whether or not they are drawn. */
    val available: Set<SeriesKey>,
    val visible: Set<SeriesKey>,
) {
    val isEmpty: Boolean get() = series.all { it.points.isEmpty() }
}

object ChartBuilder {
    private const val MAX_POINTS = 360
    private const val MIN_GAP_MS = 40 * 60_000L

    /** Fixed axis spanning a typical phone's operating range, widened only by readings outside it. */
    private const val OPERATING_MIN_C = 0f
    private const val OPERATING_MAX_C = 100f

    fun build(
        samples: List<ThermalSampleEntity>,
        events: List<HardwareEventEntity>,
        window: ChartWindow,
        now: Long,
        impactOf: (HardwareEventEntity) -> EventImpact?,
        /** Null means the default: skin, or battery when skin was never readable in the window. */
        chosen: Set<SeriesKey>? = null,
    ): ChartData {
        val start = now - window.durationMs
        val inWindow = samples.filter { it.timestamp in start..now }.sortedBy { it.timestamp }
        val bucketMs = window.durationMs / MAX_POINTS

        val built = SeriesKey.entries.mapNotNull { key ->
            val raw = inWindow.mapNotNull { s -> key.selector(s)?.takeIf { !it.isNaN() }?.let { ChartPoint(s.timestamp, it) } }
            if (raw.isEmpty()) return@mapNotNull null
            ChartSeries(key, if (raw.size > MAX_POINTS) downsample(raw, start, bucketMs) else raw)
        }
        val available = built.map { it.key }.toSet()
        val visible = chosen ?: setOf(if (SeriesKey.SKIN in available) SeriesKey.SKIN else SeriesKey.BATTERY)
        val series = built.filter { it.key in visible }

        val barMs = window.durationMs / window.bars
        val bars = inWindow.filter { it.ambientTemp != null }
            .groupBy { ((it.timestamp - start) / barMs).toInt().coerceIn(0, window.bars - 1) }
            .map { (i, group) ->
                AmbientBar(start + i * barMs, start + (i + 1) * barMs, group.mapNotNull { it.ambientTemp }.average().toFloat())
            }

        val markers = events
            .filter { it.timestamp in start..now && (it.active || it.type == HardwareEventType.THERMAL) }
            .map { e ->
                val impact = impactOf(e)
                val label = buildString {
                    append("${e.type.glyph} ${e.describe()} · ${formatTime(e.timestamp)}")
                    impact?.let { append("\nImpact: ${formatSignedTemp(it.deltaC)} in ${it.minutes.roundToInt()} min") }
                }
                ChartMarker(e.timestamp, e.type, label, impact)
            }

        val values = series.flatMap { s -> s.points.map { it.v } } + bars.map { it.value }
        val minY = minOf(OPERATING_MIN_C, floor((values.minOrNull() ?: OPERATING_MIN_C) / 10f) * 10f)
        val maxY = maxOf(OPERATING_MAX_C, ceil((values.maxOrNull() ?: OPERATING_MAX_C) / 10f) * 10f)

        return ChartData(
            start = start,
            end = now,
            series = series,
            bars = bars,
            markers = markers,
            minY = minY,
            maxY = maxY,
            gapMs = maxOf(MIN_GAP_MS, bucketMs * 3),
            window = window,
            hasSamples = inWindow.isNotEmpty(),
            available = available,
            visible = visible,
        )
    }

    private fun downsample(points: List<ChartPoint>, start: Long, bucketMs: Long): List<ChartPoint> =
        points.groupBy { (it.t - start) / bucketMs }
            .map { (_, g) -> ChartPoint(g.map { it.t }.average().toLong(), g.map { it.v }.average().toFloat()) }
            .sortedBy { it.t }
}
