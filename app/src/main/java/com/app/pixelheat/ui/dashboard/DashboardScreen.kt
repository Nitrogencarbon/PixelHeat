package com.app.pixelheat.ui.dashboard

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animate
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.FloatingToolbarScrollBehavior
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.rememberFloatingToolbarState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import com.app.pixelheat.model.HardwareEventType
import com.app.pixelheat.model.ThermalSnapshot
import com.app.pixelheat.ui.chart.ChartData
import com.app.pixelheat.ui.chart.ChartWindow
import com.app.pixelheat.ui.chart.SeriesKey
import com.app.pixelheat.ui.chart.ThermalChart
import com.app.pixelheat.ui.components.AppIcon
import com.app.pixelheat.ui.onboarding.AccessIntro
import com.app.pixelheat.ui.components.ConnectedSelector
import com.app.pixelheat.ui.components.LegendItem
import com.app.pixelheat.ui.components.SectionCard
import com.app.pixelheat.ui.components.ValueTag
import com.app.pixelheat.ui.components.motionItem
import com.app.pixelheat.ui.theme.AmbientBar
import com.app.pixelheat.ui.theme.color
import com.app.pixelheat.ui.theme.heatScale
import com.app.pixelheat.ui.theme.temperatureColor
import com.app.pixelheat.util.formatTemp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Permissions the banner still asks for. Approximate location counts as answered: the user chose it
 * in the system dialog, and Settings still offers the upgrade to precise.
 */
private fun missingPermissions(context: Context): List<String> {
    fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    return buildList {
        if (!granted(Manifest.permission.POST_NOTIFICATIONS)) add(Manifest.permission.POST_NOTIFICATIONS)
        if (!granted(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun rememberDashboardChrome(): DashboardChrome {
    val pager = rememberPagerState(pageCount = { HomeTab.entries.size })
    val live = rememberLazyGridState()
    val history = rememberLazyGridState()
    val insights = rememberLazyGridState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val toolbarBehavior = FloatingToolbarDefaults.exitAlwaysScrollBehavior(FloatingToolbarExitDirection.Bottom)
    return remember(pager, live, history, insights, scrollBehavior, toolbarBehavior) {
        DashboardChrome(
            pager,
            mapOf(HomeTab.LIVE to live, HomeTab.HISTORY to history, HomeTab.INSIGHTS to insights),
            scrollBehavior,
            toolbarBehavior,
        )
    }
}

/**
 * Second composition of the dashboard, shown behind a page while it is open so the back gesture
 * can reveal the same scroll position. It does not share the live scroll objects: attaching one
 * [LazyGridState] to two grids in the handoff frame crashes.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun DashboardUnderlay(viewModel: DashboardViewModel, source: DashboardChrome) {
    val pager = rememberPagerState(initialPage = source.pager.currentPage, pageCount = { HomeTab.entries.size })
    val liveFrom = source.grids.getValue(HomeTab.LIVE)
    val historyFrom = source.grids.getValue(HomeTab.HISTORY)
    val insightsFrom = source.grids.getValue(HomeTab.INSIGHTS)
    val live = rememberLazyGridState(liveFrom.firstVisibleItemIndex, liveFrom.firstVisibleItemScrollOffset)
    val history = rememberLazyGridState(historyFrom.firstVisibleItemIndex, historyFrom.firstVisibleItemScrollOffset)
    val insights = rememberLazyGridState(insightsFrom.firstVisibleItemIndex, insightsFrom.firstVisibleItemScrollOffset)
    val topBar = source.scrollBehavior.state
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
        rememberTopAppBarState(
            initialHeightOffsetLimit = topBar.heightOffsetLimit,
            initialHeightOffset = topBar.heightOffset,
            initialContentOffset = topBar.contentOffset,
        ),
    )
    val toolbar = source.toolbarBehavior.state
    val toolbarBehavior = FloatingToolbarDefaults.exitAlwaysScrollBehavior(
        exitDirection = FloatingToolbarExitDirection.Bottom,
        state = rememberFloatingToolbarState(
            initialOffsetLimit = toolbar.offsetLimit,
            initialOffset = toolbar.offset,
            initialContentOffset = toolbar.contentOffset,
        ),
    )
    val chrome = remember(pager, live, history, insights, scrollBehavior, toolbarBehavior) {
        DashboardChrome(
            pager,
            mapOf(HomeTab.LIVE to live, HomeTab.HISTORY to history, HomeTab.INSIGHTS to insights),
            scrollBehavior,
            toolbarBehavior,
        )
    }
    DashboardRoute(
        viewModel = viewModel,
        chrome = chrome,
        onOpenSettings = {},
        onOpenSensor = {},
        primary = false,
    )
}

internal class DashboardChrome(
    val pager: PagerState,
    val grids: Map<HomeTab, LazyGridState>,
    val scrollBehavior: TopAppBarScrollBehavior,
    val toolbarBehavior: FloatingToolbarScrollBehavior,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun DashboardRoute(
    viewModel: DashboardViewModel,
    onOpenSettings: () -> Unit,
    onOpenSensor: (String) -> Unit,
    chrome: DashboardChrome = rememberDashboardChrome(),
    // False for the predictive-back copy drawn behind a pushed screen: no back handling, no side effects.
    primary: Boolean = true,
    // False while another screen is on top. That copy of the dashboard can stay composed during the
    // transition, and its handler would otherwise take the back press meant for that screen.
    handlesSystemBack: Boolean = false,
) {
    val context = LocalContext.current
    var showIntro by remember { mutableStateOf(!viewModel.accessIntroDone) }
    if (showIntro) {
        AccessIntro(viewModel) {
            viewModel.completeAccessIntro()
            viewModel.onPermissionsChanged()
            viewModel.ensureMonitoring()
            showIntro = false
        }
        return
    }
    // Monitoring starts only once the setup cards are done.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { if (primary) viewModel.ensureMonitoring() }

    val monitor by viewModel.monitorStatus.collectAsStateWithLifecycle()
    val lifetime by viewModel.lifetime.collectAsStateWithLifecycle()
    val deviceReport by viewModel.deviceReport.collectAsStateWithLifecycle()
    val live by viewModel.live.collectAsStateWithLifecycle()
    val chart by viewModel.chart.collectAsStateWithLifecycle()
    val window by viewModel.window.collectAsStateWithLifecycle()
    val diagnosis by viewModel.diagnosis.collectAsStateWithLifecycle()
    val contributionWindow by viewModel.contributionWindow.collectAsStateWithLifecycle()
    val appHeat by viewModel.appHeat.collectAsStateWithLifecycle()
    val appHeatWindow by viewModel.appHeatWindow.collectAsStateWithLifecycle()
    val episodes by viewModel.episodes.collectAsStateWithLifecycle()

    var missing by remember { mutableStateOf(missingPermissions(context)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        missing = missingPermissions(context)
        viewModel.onPermissionsChanged()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        missing = missingPermissions(context)
    }

    val pagerState = chrome.pager
    val gridStates = chrome.grids
    val scope = rememberCoroutineScope()
    val scrollBehavior = chrome.scrollBehavior
    val toolbarBehavior = chrome.toolbarBehavior
    // History and Insights step one page left, the same movement as a right swipe.
    // Exit confirmation is only armed on Live, and only while the dashboard itself is the top screen.
    // While the toast is up the handler steps aside, so the second back is the system back-to-home animation.
    var confirmExit by remember { mutableStateOf(false) }
    val onLive by remember(pagerState) {
        derivedStateOf { pagerState.targetPage == HomeTab.LIVE.ordinal && !pagerState.isScrollInProgress }
    }
    if (handlesSystemBack) BackHandler(enabled = !onLive || !confirmExit) {
        val page = pagerState.targetPage
        if (page > HomeTab.LIVE.ordinal) {
            scope.launch { pagerState.animateScrollToPage(page - 1) }
        } else {
            if (pagerState.isScrollInProgress) scope.launch { pagerState.animateScrollToPage(page) }
            confirmExit = true
            Toast.makeText(context, "Press back again to exit", Toast.LENGTH_SHORT).show()
            scope.launch {
                delay(EXIT_TOAST_MS)
                confirmExit = false
            }
        }
    }
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
                val expandSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
                PagerTabRow(pagerState) { item ->
                    val selected = pagerState.settledPage == item.ordinal
                    HomeTabButton(item, selected = selected) {
                        scope.launch {
                            if (pagerState.currentPage == item.ordinal) {
                                // Back to the top also brings back the large title, as a fling up would.
                                val bar = scrollBehavior.state
                                launch {
                                    animate(bar.heightOffset, 0f, animationSpec = expandSpec) { value, _ ->
                                        bar.heightOffset = value
                                    }
                                }
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
            HomeGrid(
                gridStates.getValue(HomeTab.entries[page]),
                inner,
                // The pager offset is read in the graphicsLayer (draw phase), not in composition.
                Modifier.graphicsLayer {
                    val distance = pagerState.pageDistance(page)
                    val scale = lerp(1f, 0.9f, distance)
                    scaleX = scale
                    scaleY = scale
                    alpha = lerp(1f, 0.4f, distance)
                },
            ) {
                when (HomeTab.entries[page]) {
                    HomeTab.LIVE -> {
                        fullSpan("hero") { DeviceHeroCard(viewModel.identity, lifetime, deviceReport) }
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
                                MetricTile(it, onClick = { onOpenSensor(it.key) }, modifier = motionItem())
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
                        fullSpan("appheat") { AppHeatSection(appHeat, appHeatWindow, viewModel::setAppHeatWindow) }
                        fullSpan("episodes") { EpisodesSection(episodes) }
                        fullSpan("lifetime") { LifetimeSection(lifetime) }
                    }
                    HomeTab.INSIGHTS -> fullSpan("diagnosis") {
                        DiagnosisSection(
                            diagnosis,
                            contributionWindow,
                            viewModel::setContributionWindow,
                            viewModel.identity.installedAt,
                        )
                    }
                }
            }
        }
    }
}

/** Keeps the last item scrollable above the floating toolbar. */
private val ToolbarClearance = 112.dp

/** Matches the platform duration of [Toast.LENGTH_SHORT]. */
private const val EXIT_TOAST_MS = 2000L

internal enum class HomeTab(val label: String, val icon: ImageVector) {
    LIVE("Live", Icons.Rounded.Thermostat),
    HISTORY("History", Icons.Rounded.Timeline),
    INSIGHTS("Insights", Icons.Rounded.Search),
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PagerTabRow(pagerState: PagerState, button: @Composable (HomeTab) -> Unit) {
    val indicator = ToggleButtonDefaults.colors().checkedContainerColor
    var bounds by remember { mutableStateOf(List(HomeTab.entries.size) { Rect.Zero }) }
    // While the finger is on the bar the pill tracks it. A swipe on the page content keeps the stretch.
    var directDrag = remember { mutableStateOf(false) }
    Box(Modifier.dragPagerFromTabs(pagerState, { bounds }, directDrag)) {
        ElasticSelectionIndicator(pagerState, bounds, indicator, directDrag.value, Modifier.matchParentSize())
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

/**
 * Horizontal drag on the tab bar scrolls the pager. One tab-to-tab gap equals one page, so the
 * selection pill moves with the finger. Design decision: the pill slides while held, instead of
 * using the stretch it shows during a swipe on the page content. The release fling uses the
 * platform minimum fling velocity and settles at most one page away.
 */
private fun Modifier.dragPagerFromTabs(
    pagerState: PagerState,
    bounds: () -> List<Rect>,
    directDrag: MutableState<Boolean>,
): Modifier = pointerInput(pagerState) {
    coroutineScope {
        var session = 0
        var settleJob: Job? = null
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val pointerId = down.id
            val tracker = VelocityTracker()
            tracker.addPosition(down.uptimeMillis, down.position)
            var resolved = false
            var dragging = false
            var origin = pagerState.currentPage + pagerState.currentPageOffsetFraction
            var tracked = origin
            var startX = down.position.x
            val positions = Channel<Float>(Channel.CONFLATED)
            var pending: CompletableDeferred<Int>? = null
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                    tracker.addPosition(change.uptimeMillis, change.position)
                    if (!resolved && change.pressed) {
                        val dx = change.position.x - down.position.x
                        val dy = change.position.y - down.position.y
                        if (abs(dx) > viewConfiguration.touchSlop || abs(dy) > viewConfiguration.touchSlop) {
                            resolved = true
                            if (abs(dx) > abs(dy)) {
                                dragging = true
                                val captured = ++session
                                origin = pagerState.currentPage + pagerState.currentPageOffsetFraction
                                tracked = origin
                                // Measured from here, so crossing the touch slop does not jump the pill.
                                startX = change.position.x
                                directDrag.value = true
                                settleJob?.cancel()
                                val settleTarget = CompletableDeferred<Int>()
                                pending = settleTarget
                                settleJob = launch {
                                    try {
                                        pagerState.scroll(MutatePriority.UserInput) {
                                            var applied =
                                                pagerState.currentPage + pagerState.currentPageOffsetFraction
                                            for (target in positions) {
                                                applied = jumpToPagePosition(pagerState, target, applied)
                                            }
                                        }
                                        pagerState.animateScrollToPage(settleTarget.await())
                                    } finally {
                                        if (captured == session) directDrag.value = false
                                    }
                                }
                            }
                        }
                    }
                    if (dragging) {
                        change.consume()
                        val dx = change.position.x - startX
                        tracked = dragPagePosition(bounds(), origin, dx)
                        positions.trySend(tracked)
                    }
                    if (!change.pressed) break
                }
            } finally {
                positions.close()
                if (dragging) {
                    val velocity = tracker.calculateVelocity().x * tabPageDirection(bounds())
                    val last = (pagerState.pageCount - 1).coerceAtLeast(0)
                    val target = settlePage(
                        tracked,
                        velocity,
                        viewConfiguration.minimumFlingVelocity,
                        last,
                    )
                    pending?.complete(target)
                }
            }
        }
    }
}

private fun ScrollScope.jumpToPagePosition(pagerState: PagerState, target: Float, applied: Float): Float {
    val info = pagerState.layoutInfo
    val stride = info.pageSize + info.pageSpacing
    if (stride <= 0) return applied
    val max = (pagerState.pageCount - 1).coerceAtLeast(0).toFloat()
    val requested = (target.coerceIn(0f, max) - applied) * stride
    val consumed = scrollBy(requested)
    return applied + consumed / stride
}

@Composable
private fun ElasticSelectionIndicator(
    pagerState: PagerState,
    bounds: List<Rect>,
    color: Color,
    linear: Boolean,
    modifier: Modifier,
) {
    if (bounds.any { it == Rect.Zero }) return
    Box(
        modifier
            // The pager offset changes every frame while swiping: read it in the layout phase only.
            // Fills the row and places the pill at absolute coordinates, so RTL alignment cannot shift it.
            .layout { measurable, constraints ->
                val frame = indicatorFrame(pagerState, bounds, linear)
                val placeable = measurable.measure(Constraints.fixed(frame.width, frame.height))
                layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(frame.left, frame.top) }
            }
            .clip(CircleShape)
            .background(color),
    )
}

private class IndicatorFrame(val left: Int, val top: Int, val width: Int, val height: Int)

private fun indicatorFrame(pagerState: PagerState, bounds: List<Rect>, linear: Boolean): IndicatorFrame {
    val position = (pagerState.currentPage + pagerState.currentPageOffsetFraction)
        .coerceIn(0f, bounds.lastIndex.toFloat())
    val start = position.toInt().coerceIn(0, bounds.lastIndex)
    val end = (start + 1).coerceAtMost(bounds.lastIndex)
    val travel = position - start
    val from = bounds[start]
    val to = bounds[end]
    // The leading edge reaches the destination first; the trailing edge follows, so the pill stretches.
    // A drag on the bar itself skips the stretch and keeps the pill under the finger.
    val lead = if (linear) travel else (travel * 2f).coerceIn(0f, 1f)
    val trail = if (linear) travel else ((travel - 0.5f) * 2f).coerceIn(0f, 1f)
    // In RTL the next tab is on the left, so the left edge leads.
    val forward = to.left >= from.left
    val left = lerp(from.left, to.left, if (forward) trail else lead)
    val right = lerp(from.right, to.right, if (forward) lead else trail)
    return IndicatorFrame(
        left = left.roundToInt(),
        top = from.top.roundToInt(),
        width = (right - left).roundToInt().coerceAtLeast(0),
        height = from.height.roundToInt().coerceAtLeast(0),
    )
}

/** 1 when page 0 is to the left of the last page, -1 when the row is laid out right to left. */
internal fun tabPageDirection(bounds: List<Rect>): Float {
    if (bounds.size < 2) return 1f
    return if (bounds.last().center.x < bounds.first().center.x) -1f else 1f
}

/** Moves [start] by [totalDx] pixels. The gap between two tab centers is one page. */
internal fun dragPagePosition(bounds: List<Rect>, start: Float, totalDx: Float): Float {
    if (bounds.isEmpty() || bounds.any { it.width <= 0f }) return start
    val max = bounds.lastIndex.toFloat()
    val direction = tabPageDirection(bounds)
    var current = start.coerceIn(0f, max)
    var remaining = totalDx * direction
    var guard = 0
    while (abs(remaining) >= 1f && guard++ < bounds.size + 2) {
        val segment = if (remaining < 0f) (current - 0.0001f).coerceAtLeast(0f) else current.coerceAtMost(max)
        val index = segment.toInt().coerceIn(0, bounds.lastIndex)
        val next = (index + 1).coerceAtMost(bounds.lastIndex)
        val raw = abs(bounds[next].center.x - bounds[index].center.x)
        val span = if (raw >= 1f) raw else bounds[index].width.coerceAtLeast(1f)
        val roomPages = if (remaining > 0f) (next - current).coerceAtLeast(0f) else (current - index).coerceAtLeast(0f)
        val roomPx = roomPages * span
        if (roomPx <= 0.5f) break
        val step = remaining.coerceIn(-roomPx, roomPx)
        current += step / span
        remaining -= step
    }
    return current.coerceIn(0f, max)
}

/** Snaps to the nearest page, or one page toward a fling that passes [minFlingPx]. */
internal fun settlePage(position: Float, velocityPx: Float, minFlingPx: Float, lastIndex: Int): Int {
    if (lastIndex <= 0) return 0
    val target = when {
        velocityPx >= minFlingPx -> floor(position + 1f)
        velocityPx <= -minFlingPx -> ceil(position - 1f)
        else -> position.roundToInt().toFloat()
    }
    return target.toInt().coerceIn(0, lastIndex)
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
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { Box(motionItem()) { content() } }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3ExpressiveApi::class)
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
        val motion = MaterialTheme.motionScheme
        AnimatedContent(
            targetState = chart,
            transitionSpec = {
                (fadeIn(motion.defaultEffectsSpec()) togetherWith fadeOut(motion.fastEffectsSpec()))
                    .using(SizeTransform(clip = false) { _, _ -> motion.defaultSpatialSpec() })
            },
            // One key per visual branch: live data refreshes update in place, only branch swaps cross-fade.
            contentKey = { c ->
                when {
                    c == null -> 0
                    !c.hasSamples -> 1
                    c.isEmpty -> 2
                    else -> 3
                }
            },
            label = "chartContent",
        ) { current ->
            when {
                current == null -> SectionLoading("Loading history…")
                !current.hasSamples -> Text(
                    "No samples in this window yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                current.isEmpty -> Text(
                    "Turn on a temperature to draw the chart.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> ThermalChart(current)
            }
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
