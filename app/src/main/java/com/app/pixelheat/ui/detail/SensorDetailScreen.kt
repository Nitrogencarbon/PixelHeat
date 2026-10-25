package com.app.pixelheat.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.automirrored.rounded.ListAlt
import androidx.compose.material.icons.rounded.DeviceThermostat
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.app.pixelheat.data.db.HardwareEventEntity
import com.app.pixelheat.data.db.describe
import com.app.pixelheat.model.AmbientEstimate
import com.app.pixelheat.model.MonitorStatus
import com.app.pixelheat.model.PrivilegeState
import com.app.pixelheat.model.ThermalSnapshot
import com.app.pixelheat.ui.PredictiveBackRegistration
import com.app.pixelheat.ui.chart.ChartWindow
import com.app.pixelheat.ui.components.ConnectedSelector
import com.app.pixelheat.ui.components.HeatProgress
import com.app.pixelheat.ui.components.LegendItem
import com.app.pixelheat.ui.components.SectionCard
import com.app.pixelheat.ui.components.ShapeIcon
import com.app.pixelheat.ui.components.StatBlock
import com.app.pixelheat.ui.components.ValueTag
import com.app.pixelheat.ui.dashboard.SectionLoading
import com.app.pixelheat.ui.dashboard.TileModel
import com.app.pixelheat.ui.dashboard.buildTiles
import com.app.pixelheat.ui.theme.Frost
import com.app.pixelheat.ui.theme.SeriesGpu
import com.app.pixelheat.ui.theme.color
import com.app.pixelheat.ui.theme.heatScale
import com.app.pixelheat.util.formatDayTime
import com.app.pixelheat.util.formatSignedTemp
import com.app.pixelheat.util.formatTemp
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
fun SensorDetailScreen(
    viewModel: SensorDetailViewModel,
    snapshot: ThermalSnapshot?,
    monitor: MonitorStatus?,
    privilege: PrivilegeState,
    onBack: () -> Unit,
) {
    PredictiveBackRegistration()
    val metric = viewModel.metric
    val window by viewModel.window.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val signal by viewModel.signal.collectAsStateWithLifecycle()
    val traffic by viewModel.traffic.collectAsStateWithLifecycle()
    val events by viewModel.events.collectAsStateWithLifecycle()
    val tile = snapshot?.let { s -> buildTiles(s, monitor).firstOrNull { it.key == metric.key } }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(metric.title) },
                subtitle = tile?.subtitle?.let { { Text(it, maxLines = 1) } },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { inner ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = inner.calculateTopPadding() + 8.dp,
                bottom = inner.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item("hero") { HeroCard(metric, tile) }
            item("history") {
                SectionCard(title = "History", icon = Icons.Rounded.Timeline) {
                    ConnectedSelector(ChartWindow.entries, window, { it.label }, viewModel::setWindow)
                    Spacer(Modifier.height(16.dp))
                    val data = history
                    if (data == null) {
                        SectionLoading("Loading history…")
                    } else {
                        val split = traffic
                        if (metric == Metric.NETWORK && split != null) {
                            HistoryChart(
                                data = HistoryData(split.start, split.end, split.wifi, stats = null),
                                window = window,
                                kind = ValueKind.RATE,
                                color = Frost,
                                overlays = listOf(ChartSeries(split.cell, SeriesGpu)),
                            )
                            Spacer(Modifier.height(12.dp))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                LegendItem("Wi-Fi", Frost)
                                LegendItem("Mobile", SeriesGpu)
                            }
                        } else {
                            HistoryChart(data, window, metric.kind, tile?.accent ?: MaterialTheme.colorScheme.primary)
                        }
                        data.stats?.let { StatsRow(metric, it) }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Tap or drag on the chart to read a value.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (metric == Metric.NETWORK) item("signal") { SignalCard(signal, window) }
            if (snapshot != null) {
                val rows = detailRows(metric, snapshot, monitor, privilege)
                if (rows.isNotEmpty()) item("details") { DetailsCard(rows) }
                val zones = snapshot.zones
                    .filter { metric.allZones || (metric.zoneCategory != null && it.category == metric.zoneCategory) }
                    .sortedByDescending { it.celsius }
                if (zones.isNotEmpty()) {
                    item("zones") {
                        SectionCard(title = "Thermal zones (${zones.size})", icon = Icons.Rounded.DeviceThermostat) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                zones.forEach { ValueTag(it.name, formatTemp(it.celsius), it.category.heatScale().color(it.celsius)) }
                            }
                        }
                    }
                }
            }
            if (metric == Metric.AMBIENT) {
                item("calibration") {
                    val bias by viewModel.ambientBias.collectAsStateWithLifecycle()
                    AmbientCalibrationCard(snapshot?.ambient, bias, viewModel::setAmbientBias)
                }
            }
            if (metric.eventTypes.isNotEmpty()) item("events") { EventsCard(events) }
            item("about") {
                SectionCard(title = "About this sensor", icon = Icons.AutoMirrored.Rounded.HelpOutline) {
                    Text(metric.about, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun HeroCard(metric: Metric, tile: TileModel?) {
    val accent = tile?.accent ?: MaterialTheme.colorScheme.primary
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ShapeIcon(metric.icon, accent, size = 64.dp, shape = MaterialShapes.Cookie12Sided.toShape())
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        tile?.value ?: "—",
                        style = MaterialTheme.typography.displaySmallEmphasized,
                        color = accent,
                        maxLines = 1,
                    )
                    Text("Live · updates every 2 s", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            tile?.progress?.let {
                Spacer(Modifier.height(16.dp))
                HeatProgress(it, accent)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AmbientCalibrationCard(estimate: AmbientEstimate?, bias: Float, onConfirm: (Float) -> Unit) {
    val active = bias.coerceIn(BIAS_MIN, BIAS_MAX)
    var draft by remember { mutableFloatStateOf(active) }
    LaunchedEffect(active) { draft = active }
    val changed = abs(draft - active) >= 0.05f
    SectionCard(title = "Real-temperature check", icon = Icons.Rounded.DeviceThermostat) {
        Text(
            "Active correction ${formatSignedTemp(active)}. " +
                "A negative correction means the phone was reading warmer than the real air.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Choose the correction with + and −. It starts working when you confirm.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton(
                onClick = { draft = (draft - 1f).coerceIn(BIAS_MIN, BIAS_MAX) },
                enabled = draft > BIAS_MIN,
            ) {
                Icon(Icons.Rounded.Remove, contentDescription = "Lower the correction by 1 degree")
            }
            Text(
                formatSignedTemp(draft),
                style = MaterialTheme.typography.headlineSmallEmphasized,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            FilledTonalIconButton(
                onClick = { draft = (draft + 1f).coerceIn(BIAS_MIN, BIAS_MAX) },
                enabled = draft < BIAS_MAX,
            ) {
                Icon(Icons.Rounded.Add, contentDescription = "Raise the correction by 1 degree")
            }
        }
        estimate?.celsius?.let { shown ->
            if (changed) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "After confirm, the estimate reads ${formatTemp(shown - active + draft)}.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { onConfirm(draft) },
            enabled = changed,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Confirm") }
    }
}

private const val BIAS_MIN = -20f
private const val BIAS_MAX = 20f

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SignalCard(trace: SplitTrace?, window: ChartWindow) {
    SectionCard(title = "Signal strength", icon = Icons.Rounded.Wifi) {
        if (trace == null) {
            SectionLoading("Loading signal…")
            return@SectionCard
        }
        HistoryChart(
            data = HistoryData(trace.start, trace.end, trace.wifi, stats = null),
            window = window,
            kind = ValueKind.DBM,
            color = Frost,
            overlays = listOf(ChartSeries(trace.cell, SeriesGpu)),
        )
        Spacer(Modifier.height(12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            LegendItem("Wi-Fi", Frost)
            LegendItem("Cellular", SeriesGpu)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Received level in dBm: closer to zero is a stronger signal. Higher on the chart means the radio uses more power, because a weak signal makes it transmit harder. Wi-Fi is RSSI; cellular is RSRP when the radio reports it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatsRow(metric: Metric, stats: HistoryStats) {
    val kind = metric.kind
    Spacer(Modifier.height(16.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (kind != ValueKind.ON_OFF) {
            StatBlock("Min", kind.format(stats.min))
            if (!kind.stepped) StatBlock("Average", kind.format(stats.avg))
            StatBlock("Max", kind.format(stats.max), accent = metric.heatScale()?.color(stats.max))
        }
        stats.share?.let { (label, pct) -> StatBlock(label, "${pct.roundToInt()}%") }
    }
}

@Composable
private fun DetailsCard(rows: List<Pair<String, String>>) {
    SectionCard(title = "Details", icon = Icons.AutoMirrored.Rounded.ListAlt) {
        rows.forEachIndexed { i, (label, value) ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(16.dp))
                Text(value, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.End)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun EventsCard(events: List<HardwareEventEntity>) {
    SectionCard(title = "Related events · 7 days", icon = Icons.Rounded.Event) {
        if (events.isEmpty()) {
            Text("No related events recorded.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@SectionCard
        }
        Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
            events.forEachIndexed { i, e ->
                SegmentedListItem(
                    shapes = ListItemDefaults.segmentedShapes(index = i, count = events.size),
                    colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    leadingContent = { Text(e.type.glyph, style = MaterialTheme.typography.titleLarge, color = e.type.color()) },
                    supportingContent = { Text(formatDayTime(e.timestamp)) },
                ) { Text(e.describe()) }
            }
        }
    }
}
