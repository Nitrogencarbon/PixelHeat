package com.app.pixelheat.ui.detail

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.app.pixelheat.model.HeatLevel
import com.app.pixelheat.model.ThermalStatus
import com.app.pixelheat.ui.chart.ChartWindow
import com.app.pixelheat.util.formatShortDay
import com.app.pixelheat.util.formatTime
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

private const val GAP_MS = 45 * 60_000L

data class ChartSeries(val points: List<HistoryPoint>, val color: Color)

@Composable
fun HistoryChart(
    data: HistoryData,
    window: ChartWindow,
    kind: ValueKind,
    color: Color,
    modifier: Modifier = Modifier,
    overlays: List<ChartSeries> = emptyList(),
) {
    val hasPoints = data.points.isNotEmpty() || overlays.any { it.points.isNotEmpty() }
    if (!hasPoints) {
        Box(modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
            Text(
                "No data in this period yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val tooltipStyle = MaterialTheme.typography.labelMedium.copy(color = MaterialTheme.colorScheme.inverseOnSurface)
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val tooltipBg = MaterialTheme.colorScheme.inverseSurface
    val range = remember(data, kind, overlays) {
        yRange(data.points + overlays.flatMap { it.points }, kind)
    }
    var touchX by remember(data) { mutableStateOf<Float?>(null) }

    Canvas(
        modifier
            .fillMaxWidth()
            .height(220.dp)
            .pointerInput(data) {
                detectTapGestures(onTap = { touchX = it.x })
            }
            .pointerInput(data) {
                detectHorizontalDragGestures(
                    onDragStart = { touchX = it.x },
                    onHorizontalDrag = { change, _ -> touchX = change.position.x },
                )
            },
    ) {
        val widest = range.ticks.maxOf { measurer.measure(kind.axis(it), labelStyle).size.width }
        val left = max(44.dp.toPx(), widest + 8.dp.toPx())
        val bottom = size.height - 22.dp.toPx()
        val top = 28.dp.toPx()
        val right = size.width - 4.dp.toPx()
        val span = (data.end - data.start).coerceAtLeast(1L).toFloat()
        fun x(t: Long) = left + (t - data.start) / span * (right - left)
        // Signal is drawn upside down: a weaker link sits higher, because the radio spends more power to hold it.
        fun y(v: Float): Float {
            val fraction = (v - range.min) / (range.max - range.min)
            return if (kind == ValueKind.DBM) top + fraction * (bottom - top) else bottom - fraction * (bottom - top)
        }

        range.ticks.forEach { v ->
            val gy = y(v)
            drawLine(gridColor, Offset(left, gy), Offset(right, gy), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
            val layout = measurer.measure(kind.axis(v), labelStyle)
            drawText(layout, topLeft = Offset(left - layout.size.width - 6.dp.toPx(), gy - layout.size.height / 2f))
        }
        listOf(data.start, (data.start + data.end) / 2, data.end).forEachIndexed { i, t ->
            val text = if (window == ChartWindow.D7) formatShortDay(t) else formatTime(t)
            val layout = measurer.measure(text, labelStyle)
            val lx = when (i) {
                0 -> left
                1 -> x(t) - layout.size.width / 2f
                else -> right - layout.size.width
            }
            drawText(layout, topLeft = Offset(lx, bottom + 4.dp.toPx()))
        }

        val series = listOf(ChartSeries(data.points, color)) + overlays
        series.forEach { drawSeries(it, kind, ::x, ::y, top, bottom) }

        touchX?.let { tx ->
            val t = data.start + ((tx - left) / (right - left) * span).toLong()
            val nearest = series.flatMap { s -> s.points.map { it to s.color } }.minByOrNull { abs(it.first.t - t) }
            nearest?.let { (point, pointColor) ->
                drawSelection(point, x(point.t), y(point.v), top, bottom, pointColor, tooltipBg, measurer, tooltipStyle, kind)
            }
        }
    }
}

private fun DrawScope.drawSeries(
    series: ChartSeries,
    kind: ValueKind,
    x: (Long) -> Float,
    y: (Float) -> Float,
    top: Float,
    bottom: Float,
) {
    segments(series.points).forEach { seg ->
        val line = Path()
        seg.forEachIndexed { i, p ->
            val px = x(p.t)
            val py = y(p.v)
            if (i == 0) line.moveTo(px, py) else {
                if (kind.stepped) line.lineTo(px, y(seg[i - 1].v))
                line.lineTo(px, py)
            }
        }
        if (seg.size == 1) {
            drawCircle(series.color, 3.dp.toPx(), Offset(x(seg[0].t), y(seg[0].v)))
            return@forEach
        }
        val fill = Path().apply {
            addPath(line)
            lineTo(x(seg.last().t), bottom)
            lineTo(x(seg.first().t), bottom)
            close()
        }
        drawPath(
            fill,
            Brush.verticalGradient(listOf(series.color.copy(alpha = 0.28f), series.color.copy(alpha = 0f)), startY = top, endY = bottom),
        )
        drawPath(line, series.color, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

private fun DrawScope.drawSelection(
    p: HistoryPoint,
    px: Float,
    py: Float,
    top: Float,
    bottom: Float,
    color: Color,
    tooltipBg: Color,
    measurer: TextMeasurer,
    style: TextStyle,
    kind: ValueKind,
) {
    drawLine(color.copy(alpha = 0.6f), Offset(px, top), Offset(px, bottom), 1.5.dp.toPx())
    drawCircle(tooltipBg, 6.dp.toPx(), Offset(px, py))
    drawCircle(color, 4.dp.toPx(), Offset(px, py))
    val layout = measurer.measure("${kind.format(p.v)} · ${formatTime(p.t)}", style)
    val pad = 8.dp.toPx()
    val w = layout.size.width + pad * 2
    val h = layout.size.height + pad
    val bx = (px - w / 2).coerceIn(0f, size.width - w)
    drawRoundRect(tooltipBg, Offset(bx, 0f), Size(w, h), CornerRadius(h / 2))
    drawText(layout, topLeft = Offset(bx + pad, pad / 2))
}

private fun segments(points: List<HistoryPoint>): List<List<HistoryPoint>> {
    val out = mutableListOf<MutableList<HistoryPoint>>()
    points.forEach { p ->
        val current = out.lastOrNull()
        if (current == null || p.t - current.last().t > GAP_MS) out += mutableListOf(p) else current += p
    }
    return out
}

private class YRange(val min: Float, val max: Float, val ticks: List<Float>)

private fun yRange(points: List<HistoryPoint>, kind: ValueKind): YRange {
    if (points.isEmpty()) return YRange(0f, 1f, listOf(0f, 1f))
    val lo = points.minOf { it.v }
    val hi = points.maxOf { it.v }
    return when (kind) {
        ValueKind.ON_OFF -> YRange(-0.1f, 1.1f, listOf(0f, 1f))
        ValueKind.THERMAL_STATUS -> YRange(
            -0.35f,
            ThermalStatus.SHUTDOWN + 0.35f,
            (ThermalStatus.NONE..ThermalStatus.SHUTDOWN).map { it.toFloat() },
        )
        ValueKind.HEAT_LEVEL -> {
            val top = HeatLevel.HOT.ordinal.toFloat()
            YRange(-0.2f, top + 0.2f, (0..HeatLevel.HOT.ordinal).map { it.toFloat() })
        }
        ValueKind.PERCENT -> YRange(0f, 100f, listOf(0f, 25f, 50f, 75f, 100f))
        ValueKind.DBM -> YRange(-140f, -20f, listOf(-120f, -100f, -80f, -60f, -40f))
        ValueKind.TEMPERATURE -> niceRange(lo - 1f, hi + 1f)
        else -> niceRange(min(0f, lo), max(hi, lo + 1f) * 1.1f)
    }
}

private fun niceRange(lo: Float, hi: Float): YRange {
    val raw = (hi - lo) / 4f
    val magnitude = 10f.pow(floor(log10(raw.coerceAtLeast(1e-3f))))
    val step = listOf(1f, 2f, 2.5f, 5f, 10f).map { it * magnitude }.first { it >= raw }
    val start = floor(lo / step) * step
    val end = ceil(hi / step) * step
    val ticks = generateSequence(start) { it + step }.takeWhile { it <= end + step / 2 }.toList()
    return YRange(start, end, ticks)
}
