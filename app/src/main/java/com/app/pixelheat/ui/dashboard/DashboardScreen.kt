package com.app.pixelheat.ui.dashboard

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.DeviceThermostat
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FabPosition
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.FloatingToolbarExitDirection
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.material3.ToggleButtonSize
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.app.pixelheat.model.HardwareEventType
import com.app.pixelheat.model.ThermalSnapshot
import com.app.pixelheat.ui.chart.ChartData
import com.app.pixelheat.ui.chart.ChartWindow
import com.app.pixelheat.ui.chart.SeriesKey
import com.app.pixelheat.ui.chart.ThermalChart
import com.app.pixelheat.ui.components.AppIcon
import com.app.pixelheat.ui.components.ConnectedSelector
import com.app.pixelheat.ui.components.LegendItem
import com.app.pixelheat.ui.components.SectionCard
import com.app.pixelheat.ui.components.ValueTag
import com.app.pixelheat.ui.theme.AmbientBar
import com.app.pixelheat.ui.theme.color
import com.app.pixelheat.ui.theme.heatScale
import com.app.pixelheat.ui.theme.temperatureColor
import com.app.pixelheat.util.formatTemp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private val REQUESTED_PERMISSIONS = arrayOf(
    Manifest.permission.POST_NOTIFICATIONS,
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION,
)

private fun missingPermissions(context: Context) = REQUESTED_PERMISSIONS.filter {
    ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DashboardRoute(
    viewModel: DashboardViewModel,
    onOpenSettings: () -> Unit,
    onOpenSensor: (String) -> Unit,
) {
    val context = LocalContext.current
    val monitor by viewModel.monitorStatus.collectAsStateWithLifecycle()
    val lifetime by viewModel.lifetime.collectAsStateWithLifecycle()
    val live by viewModel.live.collectAsStateWithLifecycle()
    val chart by viewModel.chart.collectAsStateWithLifecycle()
    val window by viewModel.window.collectAsStateWithLifecycle()
    val diagnosis by viewModel.diagnosis.collectAsStateWithLifecycle()
    val episodes by viewModel.episodes.collectAsStateWithLifecycle()

    var missing by remember { mutableStateOf(missingPermissions(context)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        missing = missingPermissions(context)
        viewModel.onPermissionsChanged()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        missing = missingPermissions(context)
        viewModel.ensureMonitoring()
    }
    LaunchedEffect(Unit) {
        if (!viewModel.onboardingDone && missing.isNotEmpty()) {
            viewModel.markOnboarded()
            launcher.launch(missing.toTypedArray())
        }
    }

    val pagerState = rememberPagerState(pageCount = { HomeTab.entries.size })
    val gridStates = mapOf(
        HomeTab.LIVE to rememberLazyGridState(),
        HomeTab.HISTORY to rememberLazyGridState(),
        HomeTab.INSIGHTS to rememberLazyGridState(),
    )
    val scope = rememberCoroutineScope()

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val toolbarBehavior = FloatingToolbarDefaults.exitAlwaysScrollBehavior(FloatingToolbarExitDirection.Bottom)
    Scaffold(
        modifier = Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .nestedScroll(toolbarBehavior),
        floatingActionButtonPosition = FabPosition.Center,
        floatingActionButton = {
            HorizontalFloatingToolbar(
                expanded = true,
                scrollBehavior = toolbarBehavior,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors(),
            ) {
                PagerTabRow(pagerState) { item ->
                    val selected = pagerState.settledPage == item.ordinal
                    HomeTabButton(item, selected = selected) {
                        scope.launch {
                            if (pagerState.currentPage == item.ordinal) {
                                gridStates.getValue(item).animateScrollToItem(0)
                            } else {
                                pagerState.animateScrollToPage(item.ordinal)
                            }
                        }
                    }
                }
            }
        },
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(size = 44.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Pixel Heat")
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Rounded.Settings, contentDescription = "Settings")
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { inner ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = 1,
        ) { page ->
            val distance = pagerState.pageDistance(page)
            HomeGrid(
                gridStates.getValue(HomeTab.entries[page]),
                inner,
                Modifier.graphicsLayer {
                    val scale = lerp(1f, 0.9f, distance)
                    scaleX = scale
                    scaleY = scale
                    alpha = lerp(1f, 0.4f, distance)
                },
            ) {
                when (HomeTab.entries[page]) {
                    HomeTab.LIVE -> {
                        fullSpan("hero") { DeviceHeroCard(viewModel.identity, live) }
                        if (missing.isNotEmpty()) {
                            fullSpan("permissions") { PermissionCard { launcher.launch(missing.toTypedArray()) } }
                        }
                        fullSpan("live_title") {
                            Text(
                                "Live sensors",
                                style = MaterialTheme.typography.titleLargeEmphasized,
                                modifier = Modifier
                                    .padding(start = 4.dp, top = 16.dp, bottom = 4.dp)
                                    .semantics { heading() },
                            )
                        }
                        val snapshot = live
                        if (snapshot != null) {
                            items(buildTiles(snapshot, monitor), key = { it.key }) {
                                MetricTile(it, onClick = { onOpenSensor(it.key) })
                            }
                            if (snapshot.zones.isNotEmpty()) fullSpan("zones") { ZonesSection(snapshot) }
                        } else {
                            fullSpan("live_loading") { SectionLoading("Reading sensors…") }
                        }
                    }
                    HomeTab.HISTORY -> {
                        fullSpan("chart") {
                            ChartSection(chart, window, viewModel::setWindow, viewModel::toggleSeries)
                        }
                        fullSpan("episodes") { EpisodesSection(episodes) }
                        fullSpan("lifetime") { LifetimeSection(lifetime) }
                    }
                    HomeTab.INSIGHTS -> fullSpan("diagnosis") { DiagnosisSection(diagnosis) }
                }
            }
        }
    }
}

/** Keeps the last item scrollable above the floating toolbar. */
private val ToolbarClearance = 112.dp

private enum class HomeTab(val label: String, val icon: ImageVector) {
    LIVE("Live", Icons.Rounded.Thermostat),
    HISTORY("History", Icons.Rounded.Timeline),
    INSIGHTS("Insights", Icons.Rounded.Search),
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PagerTabRow(pagerState: PagerState, button: @Composable (HomeTab) -> Unit) {
    val indicator = ToggleButtonDefaults.colors().checkedContainerColor
    var bounds by remember { mutableStateOf(List(HomeTab.entries.size) { Rect.Zero }) }
    Box {
        ElasticSelectionIndicator(pagerState, bounds, indicator)
        Row {
            HomeTab.entries.forEach { item ->
                Box(
                    Modifier.onGloballyPositioned { coordinates ->
                        val position = coordinates.positionInParent()
                        val rect = Rect(
                            position.x,
                            position.y,
                            position.x + coordinates.size.width,
                            position.y + coordinates.size.height,
                        )
                        if (bounds[item.ordinal] != rect) {
                            bounds = bounds.toMutableList().also { it[item.ordinal] = rect }
                        }
                    },
                ) {
                    button(item)
                }
            }
        }
    }
}

@Composable
private fun ElasticSelectionIndicator(pagerState: PagerState, bounds: List<Rect>, color: Color) {
    if (bounds.any { it == Rect.Zero }) return
    val position = (pagerState.currentPage + pagerState.currentPageOffsetFraction)
        .coerceIn(0f, bounds.lastIndex.toFloat())
    val start = position.toInt().coerceIn(0, bounds.lastIndex)
    val end = (start + 1).coerceAtMost(bounds.lastIndex)
    val travel = position - start
    val from = bounds[start]
    val to = bounds[end]
    // The leading edge reaches the destination first; the trailing edge follows, so the pill stretches.
    val lead = (travel * 2f).coerceIn(0f, 1f)
    val trail = ((travel - 0.5f) * 2f).coerceIn(0f, 1f)
    val left = lerp(from.left, to.left, trail)
    val right = lerp(from.right, to.right, lead)
    val density = LocalDensity.current
    Box(
        Modifier
            .offset { IntOffset(left.roundToInt(), from.top.roundToInt()) }
            .size(
                width = with(density) { (right - left).toDp() },
                height = with(density) { from.height.toDp() },
            )
            .clip(CircleShape)
            .background(color),
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun HomeTabButton(item: HomeTab, selected: Boolean, onClick: () -> Unit) {
    ToggleButton(
        checked = selected,
        onCheckedChange = { onClick() },
        modifier = Modifier.semantics { contentDescription = item.label },
        buttonSize = ToggleButtonSize.Small,
        shapes = ToggleButtonShapes(
            shape = CircleShape,
            pressedShape = ToggleButtonDefaults.pressedShape,
            checkedShape = CircleShape,
        ),
        colors = ToggleButtonDefaults.colors(
            containerColor = Color.Transparent,
            checkedContainerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        TabIcon(item)
        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        Text(item.label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

@Composable
private fun TabIcon(item: HomeTab) {
    if (item != HomeTab.INSIGHTS) {
        Icon(item.icon, contentDescription = null)
        return
    }
    Box(Modifier.size(24.dp)) {
        Icon(
            Icons.Rounded.Search,
            contentDescription = null,
            modifier = Modifier.align(Alignment.CenterStart).size(22.dp),
        )
        Icon(
            Icons.Rounded.AutoAwesome,
            contentDescription = null,
            modifier = Modifier.align(Alignment.TopEnd).size(11.dp),
        )
    }
}

private fun PagerState.pageDistance(page: Int): Float =
    abs((currentPage - page) + currentPageOffsetFraction).coerceIn(0f, 1f)

@Composable
private fun HomeGrid(
    state: LazyGridState,
    inner: PaddingValues,
    modifier: Modifier = Modifier,
    content: LazyGridScope.() -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 168.dp),
        state = state,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = inner.calculateTopPadding() + 8.dp,
            bottom = inner.calculateBottomPadding() + ToolbarClearance,
        ),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

private fun LazyGridScope.fullSpan(key: String, content: @Composable () -> Unit) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChartSection(
    chart: ChartData?,
    window: ChartWindow,
    onWindow: (ChartWindow) -> Unit,
    onToggleSeries: (SeriesKey) -> Unit,
) {
    SectionCard(title = "Thermal history", icon = Icons.Rounded.Thermostat) {
        ConnectedSelector(ChartWindow.entries, window, { it.label }, onWindow)
        Spacer(Modifier.height(12.dp))
        val available = chart?.available ?: SeriesKey.entries.toSet()
        val visible = chart?.visible.orEmpty()
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SeriesKey.entries.forEach { key ->
                FilterChip(
                    selected = key in visible,
                    onClick = { onToggleSeries(key) },
                    enabled = key in available,
                    label = { Text(key.label) },
                    leadingIcon = { SeriesDot(key.color) },
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        when {
            chart == null -> SectionLoading("Loading history…")
            !chart.hasSamples -> Text(
                "No samples in this window yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            chart.isEmpty -> Text(
                "Turn on a temperature to draw the chart.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> ThermalChart(chart)
        }
        if (chart != null && chart.bars.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            LegendItem("Outside (estimate)", AmbientBar.copy(alpha = 0.6f), square = true)
        }
        val types = chart?.markers?.map { it.type }?.distinct().orEmpty()
        if (types.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                types.forEach { EventLegend(it) }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Tap a temperature to show or hide it. Tap or drag on the chart to read values.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SeriesDot(color: Color) {
    Box(
        Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(color),
    )
}

@Composable
private fun EventLegend(type: HardwareEventType) {
    Text(
        "${type.glyph} ${type.label}",
        style = MaterialTheme.typography.labelMedium,
        color = type.color(),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ZonesSection(snapshot: ThermalSnapshot) {
    SectionCard(title = "All thermal zones (${snapshot.zones.size})", icon = Icons.Rounded.DeviceThermostat) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            snapshot.zones.sortedByDescending { it.celsius }.forEach { z ->
                ValueTag(z.name, formatTemp(z.celsius), z.category.heatScale().color(z.celsius))
            }
        }
    }
}
