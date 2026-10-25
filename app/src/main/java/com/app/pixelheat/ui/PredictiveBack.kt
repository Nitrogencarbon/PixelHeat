package com.app.pixelheat.ui

import android.content.res.Configuration
import android.os.SystemClock
import android.view.RoundedCorner
import android.view.View
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.NavigationEventTransitionState
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.NavigationEventState
import androidx.navigationevent.compose.rememberNavigationEventState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull

private val LocalPredictiveBackHost = staticCompositionLocalOf<PredictiveBackHost?> { null }

/**
 * Registers this destination for the system back gesture. Composed from a pushed screen, so it
 * sits ahead of [androidx.navigation3.ui.NavDisplay]'s own handler, and behind any back handler
 * that screen composes later (menus, the intro wizard).
 */
@Composable
fun PredictiveBackRegistration() {
    val host = checkNotNull(LocalPredictiveBackHost.current) {
        "Predictive back is registered inside Pixel Heat navigation."
    }
    // While NavDisplay is popping this screen (toolbar back) it is no longer RESUMED and must not
    // take a second back. During its own post-commit animation it stays registered to swallow one.
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    NavigationBackHandler(
        state = host.events,
        isBackEnabled = host.settling || lifecycle.isAtLeast(Lifecycle.State.RESUMED),
        onBackCancelled = host::onCancelled,
        onBackCompleted = host::onCommitted,
    )
    DisposableEffect(host) { onDispose { host.onClosingDisposed() } }
}

/**
 * Draws the previous destination behind the current one and drives both with the Android 17
 * cross-activity back gesture. [onSeamlessPop] runs when the post-commit animation finishes; the
 * pop itself must not play a second transition.
 */
@Composable
fun PredictiveBackContainer(
    showUnderlay: Boolean,
    onSeamlessPop: () -> Unit,
    underlay: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val events = rememberNavigationEventState(currentInfo = NavigationEventInfo.None)
    val scope = rememberCoroutineScope()
    val host = remember(events, scope) { PredictiveBackHost(events, scope) }
    SideEffect { host.popSeamlessly = onSeamlessPop }

    val configuration = LocalConfiguration.current
    val dark = configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    val density = LocalDensity.current
    val view = LocalView.current

    CompositionLocalProvider(LocalPredictiveBackHost provides host) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val metrics = BackMetrics(
                widthPx = constraints.maxWidth.toFloat(),
                heightPx = constraints.maxHeight.toFloat(),
                marginPx = with(density) { 8.dp.toPx() },
                enteringOffsetPx = with(density) { 96.dp.toPx() },
                cornerRadiusPx = windowCornerRadiusPx(view),
                maxScrimAlpha = if (dark) MAX_SCRIM_ALPHA_DARK else MAX_SCRIM_ALPHA_LIGHT,
            )
            host.metrics = metrics

            val event = (events.transitionState as? NavigationEventTransitionState.InProgress)?.latestEvent
            SideEffect {
                if (event != null) host.onProgress(event)
                if (event != null && !host.settling) host.session.track(event)
            }
            LifecycleEventEffect(Lifecycle.Event.ON_STOP) { host.finishNow() }
            val live = if (event != null && !host.settling && metrics.widthPx > 0f) {
                host.session.preview(event, metrics)
            } else {
                null
            }
            val frame = host.animated ?: live

            // settling stays true until NavDisplay has dropped the closing screen, so the revealed
            // dashboard does not disappear first. Outside a gesture the copy stays composed but is
            // not drawn: a forward push would otherwise fade the real dashboard over a still copy.
            val reveal = frame != null || host.settling
            if (showUnderlay || host.settling) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .semantics { hideFromAccessibility() }
                        // A picture of the previous screen: a keyboard or D-pad must not move focus into it.
                        .focusProperties { onEnter = { cancelFocusChange() } }
                        .focusGroup()
                        .graphicsLayer { alpha = if (reveal) 1f else 0f }
                        .backSurface(frame, closing = false),
                ) { underlay() }
                if (frame != null && frame.scrimAlpha > 0f) {
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = frame.scrimAlpha)))
                }
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .backSurface(frame, closing = true),
            ) { content() }
            if (host.settling) {
                // The faded closing screen still covers the dashboard; it must not take taps.
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                                }
                            }
                        },
                )
            }
        }
    }
}

private class PredictiveBackHost(
    val events: NavigationEventState<NavigationEventInfo.None>,
    private val scope: kotlinx.coroutines.CoroutineScope,
) {
    var settling by mutableStateOf(false)
    var animated by mutableStateOf<BackFrame?>(null)
    var popSeamlessly: () -> Unit = {}
    var metrics: BackMetrics = BackMetrics(0f, 0f, 0f, 0f, 0f, 0f)
    val session = GestureSession()
    private var closingGone: CompletableDeferred<Unit>? = null

    /** The running settle animation. A cancelled one must not clean up after its replacement. */
    private var job: Job? = null
    private var cancelling = false
    private var committing = false
    private var popped = false

    fun onClosingDisposed() {
        closingGone?.complete(Unit)
    }

    private var lastEvent: NavigationEvent? = null
    private var eventAtCancel: NavigationEvent? = null

    /** The gesture state can still hold the cancelled swipe for a frame; only a new event interrupts. */
    fun onProgress(event: NavigationEvent) {
        if (cancelling && !sameEvent(event, eventAtCancel)) interruptCancel()
        lastEvent = event
    }

    private fun sameEvent(a: NavigationEvent, b: NavigationEvent?) =
        b != null && a.frameTimeMillis == b.frameTimeMillis && a.progress == b.progress && a.touchX == b.touchX

    /** A new swipe or back during the cancel spring takes over from wherever the screen is. */
    fun interruptCancel() {
        if (!cancelling) return
        job?.cancel()
        job = null
        cancelling = false
        animated = null
        session.reset()
        settling = false
    }

    /**
     * The activity is stopping mid-commit, for example to rotate. Its scope is about to go, and the
     * back stack is saved right after this: pop now so the screen the user dismissed does not return.
     */
    fun finishNow() {
        if (committing && !popped) {
            popped = true
            popSeamlessly()
        }
    }

    fun onCancelled() {
        if (settling) return
        val from = session.rawProgress
        if (backGesture(from) < 0.02f || metrics.widthPx <= 0f) {
            session.reset()
            return
        }
        settling = true
        cancelling = true
        eventAtCancel = lastEvent
        animated = session.lastFrame
        lateinit var self: Job
        self = scope.launch {
            try {
                // BackProgressAnimator springs the raw progress; the gesture curve is applied on top.
                animate(
                    initialValue = from,
                    targetValue = 0f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMedium,
                    ),
                ) { progress, _ ->
                    animated = session.frameAt(progress, metrics)
                }
            } finally {
                if (job === self) {
                    job = null
                    cancelling = false
                    animated = null
                    session.reset()
                    settling = false
                }
            }
        }
        job = self
    }

    fun onCommitted() {
        interruptCancel()
        if (settling) return
        if (metrics.widthPx <= 0f) {
            popSeamlessly()
            session.reset()
            return
        }
        val start = session.lastFrame ?: session.frameAt(0f, metrics)
        val velocity = flingVisualVelocity(
            session.tracker.velocity(),
            session.easedProgress,
            edgeVelocityFactor(session.edge),
        )
        // The handler stays registered, so a second swipe cannot fall through to NavDisplay.
        settling = true
        committing = true
        popped = false
        animated = start
        lateinit var self: Job
        self = scope.launch {
            try {
                var fling = 1f
                var playhead = 0f
                supervisorScope {
                    val flingJob = launch {
                        animate(
                            initialValue = 1f,
                            targetValue = 1f,
                            initialVelocity = velocity,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioLowBouncy,
                                stiffness = Spring.StiffnessLow,
                            ),
                        ) { value, _ ->
                            fling = value.coerceIn(0.01f, 1f)
                            if (playhead >= 1f) {
                                animated = postCommitFrame(start.closing, start.entering, metrics, 1f, fling)
                            }
                        }
                    }
                    animate(
                        initialValue = 0f,
                        targetValue = 1f,
                        animationSpec = tween(POST_COMMIT_MS, easing = LinearEasing),
                    ) { linear, _ ->
                        playhead = linear
                        animated = postCommitFrame(start.closing, start.entering, metrics, linear, fling)
                    }
                    // The playhead is over. Let the fling spring settle so the release is not a scale jump.
                    playhead = 1f
                    animated = postCommitFrame(start.closing, start.entering, metrics, 1f, fling)
                    flingJob.join()
                }
                // Identity pose, closing screen fully faded. Hold it until NavDisplay has disposed
                // that screen: clearing the transform earlier lets it snap back to full size.
                animated = postCommitFrame(start.closing, start.entering, metrics, 1f, 1f)
                val gone = CompletableDeferred<Unit>()
                closingGone = gone
                if (!popped) {
                    popped = true
                    popSeamlessly()
                }
                withTimeoutOrNull(HANDOFF_TIMEOUT_MS) { gone.await() }
                withFrameNanos { }
            } finally {
                if (job === self) {
                    job = null
                    committing = false
                    closingGone = null
                    animated = null
                    session.reset()
                    settling = false
                }
            }
        }
        job = self
    }
}

private class GestureSession {
    private var initialTouchY = Float.NaN
    var edge: Int = EDGE_LEFT
        private set
    var rawProgress: Float = 0f
        private set
    var easedProgress: Float = 0f
        private set
    var lastFrame: BackFrame? = null
        private set
    val tracker = ProgressVelocityTracker()
    private var lastTrackedTime = Long.MIN_VALUE
    private var lastTrackedProgress = Float.NaN

    fun track(event: NavigationEvent) {
        if (event.frameTimeMillis == lastTrackedTime && event.progress == lastTrackedProgress) return
        lastTrackedTime = event.frameTimeMillis
        lastTrackedProgress = event.progress
        val time = if (event.frameTimeMillis > 0L) event.frameTimeMillis else SystemClock.uptimeMillis()
        tracker.add(time, backGesture(event.progress))
    }

    fun preview(event: NavigationEvent, metrics: BackMetrics): BackFrame {
        if (initialTouchY.isNaN()) {
            initialTouchY = event.touchY
            edge = event.swipeEdge
        }
        return frameAt(event.progress, metrics, event.touchY)
    }

    fun frameAt(rawProgress: Float, metrics: BackMetrics, touchY: Float = initialTouchY): BackFrame {
        val touch = if (touchY.isNaN()) 0f else touchY
        val origin = if (initialTouchY.isNaN()) touch else initialTouchY
        this.rawProgress = rawProgress
        easedProgress = backGesture(rawProgress)
        val frame = previewFrame(rawProgress, touch, origin, edge, metrics)
        lastFrame = frame
        return frame
    }

    fun reset() {
        initialTouchY = Float.NaN
        edge = EDGE_LEFT
        rawProgress = 0f
        easedProgress = 0f
        lastFrame = null
        lastTrackedTime = Long.MIN_VALUE
        lastTrackedProgress = Float.NaN
        tracker.reset()
    }
}

private fun windowCornerRadiusPx(view: View): Float {
    val insets = view.rootWindowInsets ?: return 0f
    val corners = intArrayOf(
        RoundedCorner.POSITION_TOP_LEFT,
        RoundedCorner.POSITION_TOP_RIGHT,
        RoundedCorner.POSITION_BOTTOM_LEFT,
        RoundedCorner.POSITION_BOTTOM_RIGHT,
    )
    return corners.maxOf { insets.getRoundedCorner(it)?.radius?.toFloat() ?: 0f }
}

private fun Modifier.backSurface(frame: BackFrame?, closing: Boolean): Modifier {
    if (frame == null || frame.fullWidth <= 0f) return this
    val rect = if (closing) frame.closing else frame.entering
    val alpha = if (closing) frame.closingAlpha else 1f
    val radius = frame.cornerRadiusPx
    return graphicsLayer {
        val scale = rect.width / frame.fullWidth
        transformOrigin = TransformOrigin(0f, 0f)
        scaleX = scale
        scaleY = scale
        translationX = rect.left
        translationY = rect.top
        this.alpha = alpha
        clip = radius > 0f
        if (radius > 0f) shape = WindowCornerShape(radius)
    }
}

private class WindowCornerShape(private val radiusPx: Float) : Shape {
    override fun createOutline(size: androidx.compose.ui.geometry.Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(
            RoundRect(0f, 0f, size.width, size.height, CornerRadius(radiusPx)),
        )
}
