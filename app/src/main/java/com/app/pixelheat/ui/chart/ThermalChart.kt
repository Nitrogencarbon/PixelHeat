package com.app.pixelheat.ui.chart

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.repeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.app.pixelheat.ui.theme.AmbientBar
import com.app.pixelheat.ui.theme.color
import com.app.pixelheat.util.fmt
import com.app.pixelheat.util.formatShortDay
import com.app.pixelheat.util.formatTemp
import com.app.pixelheat.util.formatTime
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.ceil

private const val SELECTION_TIMEOUT_MS = 4_000L
private const val RECENT_LABEL_MS = 10 * 60_000L

/** Odd, so the reverse-repeating pulse ends fully opaque. */
private const val PULSE_ITERATIONS = 7

private sealed interface ChartSelection {
    data class Time(val t: Long) : ChartSelection
    data class Marker(val placed: PlacedMarker) : ChartSelection
}

private data class PlacedMarker(val marker: ChartMarker, val x: Float, val y: Float)

private class ChartGeometry(size: IntSize, density: Density, val data: ChartData) {
    private fun Float.dp2px(d: Density) = this * d.density
    val width = size.width.toFloat()
    val height = size.height.toFloat()
    val left = 40f.dp2px(density)
    val right = width - 8f.dp2px(density)
    val top = 52f.dp2px(density)
    val bottom = height - 22f.dp2px(density)
    val lanes = listOf(14f.dp2px(density), 36f.dp2px(density))
    val badgeR = 10f.dp2px(density)
    private val minGap = 2.3f * badgeR
    private val span = (data.end - data.start).coerceAtLeast(1L).toFloat()

    fun x(t: Long) = left + (t - data.start) / span * (right - left)
    fun y(v: Float) = bottom - (v - data.minY) / (data.maxY - data.minY) * (bottom - top)
    fun timeAt(px: Float): Long = data.start + (((px - left) / (right - left)).coerceIn(0f, 1f) * span).toLong()

    /** Greedy two-lane layout: impactful markers are placed first so they win collisions. */
    fun layoutMarkers(): List<PlacedMarker> {
        val placed = mutableListOf<PlacedMarker>()
        val ordered = data.markers.sortedWith(
            compareByDescending<ChartMarker> { it.significant }.thenByDescending { it.impact?.deltaC ?: 0f },
        )
        for (m in ordered) {
            val mx = x(m.t)
            val lane = lanes.firstOrNull { ly -> placed.none { it.y == ly && abs(it.x - mx) < minGap } } ?: continue
            placed += PlacedMarker(m, mx, lane)
        }
        return placed
    }
}

private data class ChartColors(
    val grid: Color,
    val label: Color,
    val tooltipBg: Color,
    val tooltipText: Color,
    val crosshair: Color,
)

@Composable
fun ThermalChart(data: ChartData, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer(cacheSize = 64)
    val density = LocalDensity.current
    var size by remember { mutableStateOf(IntSize.Zero) }
    var selection by remember { mutableStateOf<ChartSelection?>(null) }
    LaunchedEffect(selection) {
        if (selection != null) {
            delay(SELECTION_TIMEOUT_MS)
            selection = null
        }
    }

    val scheme = MaterialTheme.colorScheme
    val colors = ChartColors(
        grid = scheme.outlineVariant.copy(alpha = 0.45f),
        label = scheme.onSurfaceVariant,
        tooltipBg = scheme.inverseSurface.copy(alpha = 0.94f),
        tooltipText = scheme.inverseOnSurface,
        crosshair = scheme.onSurface.copy(alpha = 0.5f),
    )
    val labelStyle = TextStyle(fontSize = 10.sp, color = colors.label)
    val tooltipStyle = TextStyle(fontSize = 11.sp, color = colors.tooltipText)
    val glyphStyle = TextStyle(fontSize = 11.sp)

    val geometry = remember(size, data, density) {
        if (size.width == 0 || size.height == 0) null else ChartGeometry(size, density, data)
    }
    val placed = remember(geometry) { geometry?.layoutMarkers().orEmpty() }
    val latestRecent = data.markers.filter { data.end - it.t < RECENT_LABEL_MS }.maxOfOrNull { it.t }
    // A few pulses per new event, not a loop: the chart stays composed on the neighbouring pages too.
    // The value is read inside the draw block below, so it only redraws: it never recomposes the chart.
    val pulse = remember { Animatable(1f) }
    LaunchedEffect(latestRecent) {
        if (latestRecent == null) return@LaunchedEffect
        pulse.snapTo(0.35f)
        pulse.animateTo(1f, repeatable(PULSE_ITERATIONS, tween(900), RepeatMode.Reverse))
    }
    val currentGeometry by rememberUpdatedState(geometry)
    val currentPlaced by rememberUpdatedState(placed)

    Spacer(
        modifier = modifier
            .fillMaxWidth()
            .height(280.dp)
            .onSizeChanged { size = it }
            // Keyed on Unit: new samples rebuild the geometry, and restarting here would cancel a drag.
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val g = currentGeometry ?: return@detectTapGestures
                    val hit = currentPlaced.minByOrNull { abs(it.x - offset.x) + abs(it.y - offset.y) }
                        ?.takeIf { abs(it.x - offset.x) < g.badgeR * 1.8f && abs(it.y - offset.y) < g.badgeR * 1.8f }
                    selection = when {
                        hit != null -> ChartSelection.Marker(hit)
                        offset.x in g.left..g.right -> ChartSelection.Time(g.timeAt(offset.x))
                        else -> null
                    }
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ ->
                    val g = currentGeometry ?: return@detectHorizontalDragGestures
                    selection = ChartSelection.Time(g.timeAt(change.position.x))
                }
            }
            .drawWithCache {
                // Series paths only depend on the geometry: build them once, not on every pulse frame.
                val seriesPaths = geometry?.let(::buildSeriesPaths).orEmpty()
                onDrawBehind {
                    val g = geometry ?: return@onDrawBehind
                    drawHotZone(g)
                    drawGrid(g, measurer, labelStyle, colors)
                    drawBars(g)
                    drawSeries(seriesPaths)
                    drawMarkers(g, placed, measurer, glyphStyle, tooltipStyle, colors, pulse.value)
                    when (val sel = selection) {
                        is ChartSelection.Time -> drawTimeSelection(g, sel.t, measurer, tooltipStyle, colors)
                        is ChartSelection.Marker -> drawBubble(g, sel.placed.x, sel.placed.y + g.badgeR + 4f, sel.placed.marker.label, measurer, tooltipStyle, colors)
                        null -> Unit
                    }
                    if (data.isEmpty) {
                        val layout = measurer.measure("Collecting data.", labelStyle.copy(fontSize = 13.sp))
                        drawText(layout, topLeft = Offset((g.width - layout.size.width) / 2, (g.top + g.bottom - layout.size.height) / 2))
                    }
                }
            },
    )
}

/** Skin and battery hit Android's Severe status around here: too hot to hold or use normally. */
private const val TOO_HOT_C = 45f
private val TooHot = Color(0xFFE53935)

private fun DrawScope.drawHotZone(g: ChartGeometry) {
    if (TOO_HOT_C >= g.data.maxY) return
    val y = g.y(TOO_HOT_C.coerceAtLeast(g.data.minY))
    drawRect(TooHot.copy(alpha = 0.12f), topLeft = Offset(g.left, g.top), size = Size(g.right - g.left, y - g.top))
}

private fun niceStep(range: Float): Float = when {
    range <= 12f -> 2f
    range <= 30f -> 5f
    range <= 60f -> 10f
    else -> 20f
}

private fun DrawScope.drawGrid(g: ChartGeometry, measurer: TextMeasurer, style: TextStyle, colors: ChartColors) {
    val data = g.data
    val step = niceStep(data.maxY - data.minY)
    var v = ceil(data.minY / step) * step
    while (v <= data.maxY + 0.01f) {
        val y = g.y(v)
        drawLine(colors.grid, Offset(g.left, y), Offset(g.right, y), strokeWidth = 1f)
        val layout = measurer.measure("${v.toInt()}°", style)
        drawText(layout, topLeft = Offset(g.left - layout.size.width - 6f, y - layout.size.height / 2f))
        v += step
    }
    val ticks = 4
    val multiDay = data.window.durationMs > 2 * 24 * 60 * 60_000L
    for (i in 0..ticks) {
        val t = data.start + (data.end - data.start) * i / ticks
        val text = if (multiDay) formatShortDay(t) else formatTime(t)
        val layout = measurer.measure(text, style)
        val x = (g.x(t) - layout.size.width / 2f).coerceIn(g.left - 8f, g.right - layout.size.width)
        drawText(layout, topLeft = Offset(x, g.bottom + 6f))
    }
}

private fun DrawScope.drawBars(g: ChartGeometry) {
    val base = g.bottom
    g.data.bars.forEach { bar ->
        val x0 = g.x(bar.start)
        val x1 = g.x(bar.end)
        val w = (x1 - x0) * 0.72f
        val top = g.y(bar.value.coerceIn(g.data.minY, g.data.maxY))
        drawRoundRect(
            color = AmbientBar.copy(alpha = 0.28f),
            topLeft = Offset(x0 + (x1 - x0 - w) / 2f, top),
            size = Size(w, base - top),
            cornerRadius = CornerRadius(4f, 4f),
        )
    }
}

private class SeriesPath(val path: Path, val color: Color, val last: Offset?)

private fun buildSeriesPaths(g: ChartGeometry): List<SeriesPath> = g.data.series.map { s ->
    val path = Path()
    for (i in s.points.indices) {
        val p = s.points[i]
        val x = g.x(p.t)
        val y = g.y(p.v)
        if (i == 0 || p.t - s.points[i - 1].t > g.data.gapMs) path.moveTo(x, y) else path.lineTo(x, y)
    }
    SeriesPath(path, s.key.color, s.points.lastOrNull()?.let { Offset(g.x(it.t), g.y(it.v)) })
}

private fun DrawScope.drawSeries(paths: List<SeriesPath>) {
    paths.forEach { sp ->
        drawPath(sp.path, sp.color, style = Stroke(width = 2.2f * density, cap = StrokeCap.Round, join = StrokeJoin.Round))
        sp.last?.let { drawCircle(sp.color, radius = 3.5f * density, center = it) }
    }
}

private fun DrawScope.drawMarkers(
    g: ChartGeometry,
    placed: List<PlacedMarker>,
    measurer: TextMeasurer,
    glyphStyle: TextStyle,
    labelStyle: TextStyle,
    colors: ChartColors,
    pulse: Float,
) {
    val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
    placed.forEach { p ->
        val color = p.marker.type.color()
        val emphasis = if (p.marker.significant) 1f else 0.55f
        drawLine(
            color.copy(alpha = 0.35f * emphasis),
            Offset(p.x, p.y + g.badgeR),
            Offset(p.x, g.bottom),
            strokeWidth = if (p.marker.significant) 2f else 1f,
            pathEffect = dash,
        )
        val recent = g.data.end - p.marker.t < RECENT_LABEL_MS
        val alpha = if (recent) pulse else emphasis
        if (p.marker.significant) {
            drawCircle(color.copy(alpha = 0.25f * alpha), radius = g.badgeR * 1.45f, center = Offset(p.x, p.y))
        }
        drawCircle(color.copy(alpha = 0.9f * alpha), radius = g.badgeR, center = Offset(p.x, p.y))
        val glyph = measurer.measure(p.marker.type.glyph, glyphStyle)
        drawText(glyph, topLeft = Offset(p.x - glyph.size.width / 2f, p.y - glyph.size.height / 2f))

        if (recent) {
            val short = p.marker.label.lineSequence().first()
            val layout = measurer.measure(short, labelStyle.copy(color = colors.label, fontSize = 9.sp))
            val lx = if (p.x + g.badgeR + layout.size.width + 4f < g.right) p.x + g.badgeR + 4f else p.x - g.badgeR - 4f - layout.size.width
            drawText(layout, topLeft = Offset(lx, p.y - layout.size.height / 2f), alpha = pulse)
        }
    }
}

private fun DrawScope.drawTimeSelection(
    g: ChartGeometry,
    t: Long,
    measurer: TextMeasurer,
    style: TextStyle,
    colors: ChartColors,
) {
    val x = g.x(t)
    drawLine(colors.crosshair, Offset(x, g.top), Offset(x, g.bottom), strokeWidth = 1.2f)
    val lines = mutableListOf(formatTime(t))
    g.data.series.forEach { s ->
        val nearest = s.points.minByOrNull { abs(it.t - t) } ?: return@forEach
        if (abs(nearest.t - t) > g.data.gapMs) return@forEach
        drawCircle(s.key.color, radius = 4.5f * density, center = Offset(g.x(nearest.t), g.y(nearest.v)))
        lines += "${s.key.label}  ${formatTemp(nearest.v)}"
    }
    g.data.bars.firstOrNull { t in it.start..it.end }?.let { lines += "Outside  ${it.value.fmt(1)} °C" }
    val tolerance = (g.data.end - g.data.start) / 60
    g.data.markers.filter { abs(it.t - t) <= tolerance }.take(2).forEach { lines += it.label.lineSequence().first() }
    drawBubble(g, x, g.top, lines.joinToString("\n"), measurer, style, colors)
}

private fun DrawScope.drawBubble(
    g: ChartGeometry,
    anchorX: Float,
    anchorY: Float,
    text: String,
    measurer: TextMeasurer,
    style: TextStyle,
    colors: ChartColors,
) {
    val layout = measurer.measure(text, style)
    val pad = 8f * density
    val w = layout.size.width + pad * 2
    val h = layout.size.height + pad * 2
    val x = if (anchorX + 10f + w <= g.width) anchorX + 10f else (anchorX - 10f - w).coerceAtLeast(0f)
    val y = anchorY.coerceIn(0f, (g.height - h).coerceAtLeast(0f))
    drawRoundRect(colors.tooltipBg, topLeft = Offset(x, y), size = Size(w, h), cornerRadius = CornerRadius(10f * density))
    drawText(layout, topLeft = Offset(x + pad, y + pad))
}
