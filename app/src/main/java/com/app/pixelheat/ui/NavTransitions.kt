package com.app.pixelheat.ui

import android.view.RoundedCorner
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.util.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntryDecorator
import androidx.navigation3.scene.Scene
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import androidx.navigationevent.NavigationEventTransitionState
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import kotlinx.coroutines.flow.first

private const val AXIS_MS = 300
private const val AXIS_SHIFT_DP = 30
private const val PREDICTIVE_MS = 450
private const val EXIT_SCALE = 0.85f
private const val ENTER_SCALE = 0.9f
private const val SCRIM_ALPHA = 0.65f
private const val ENTER_SHIFT_FRACTION = 6
private const val RELEASE_MS = 400
private val FallbackCorner = 36.dp
private val MinCorner = 32.dp

/** Front-loaded like the system back animation: most of the shrink happens in the first part of the swipe. */
private val BackEasing = CubicBezierEasing(0.1f, 0.6f, 0.3f, 1f)
private val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

/** Which kind of transition is running, so the entry decorator only dims the page revealed by the back gesture. */
class NavMotion {
    var predictive = false

    /** Set when a back gesture is released past the commit point, as opposed to cancelled. */
    var popCommitted = false
}

/** Material shared-axis X: content slides a short distance along the navigation direction while cross-fading. */
fun <T : Any> AnimatedContentTransitionScope<Scene<T>>.sharedAxisX(
    forward: Boolean,
    density: Float,
    motion: NavMotion,
): ContentTransform {
    motion.predictive = false
    val sign = if (forward) 1 else -1
    val shift = (AXIS_SHIFT_DP * density).toInt()
    val enter = slideInHorizontally(tween(AXIS_MS, easing = FastOutSlowInEasing)) { sign * shift } +
        fadeIn(tween(AXIS_MS * 2 / 3, delayMillis = AXIS_MS / 3, easing = LinearEasing))
    val exit = slideOutHorizontally(tween(AXIS_MS, easing = FastOutSlowInEasing)) { -sign * shift } +
        fadeOut(tween(AXIS_MS / 3, easing = LinearEasing))
    return enter togetherWith exit
}

/**
 * Predictive back matching the system cross-activity animation: the current page shrinks toward the
 * center with display-like rounded corners, while the previous page is revealed behind a scrim
 * (its position and scale are handled by [rememberBackMotionDecorator]).
 */
fun <T : Any> AnimatedContentTransitionScope<Scene<T>>.predictiveBack(motion: NavMotion): ContentTransform {
    motion.predictive = true
    val enter = EnterTransition.None
    val exit = scaleOut(tween(PREDICTIVE_MS, easing = BackEasing), targetScale = EXIT_SCALE) +
        fadeOut(tween(PREDICTIVE_MS / 4, delayMillis = PREDICTIVE_MS * 3 / 4, easing = LinearEasing))
    return enter togetherWith exit
}

/**
 * Rounds the corners of the leaving page (up to the display's own corner radius) and places, scales and dims
 * the page being revealed by a back gesture.
 */
@Composable
fun <T : Any> rememberBackMotionDecorator(motion: NavMotion): NavEntryDecorator<T> = remember(motion) {
    NavEntryDecorator { entry ->
        val view = LocalView.current
        val density = LocalDensity.current
        val corner = remember(view, density) {
            view.rootWindowInsets?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius
                ?.takeIf { it > 0 }?.let { with(density) { it.toDp() } } ?: FallbackCorner
        }
        val transition = LocalNavAnimatedContentScope.current.transition
        // The page is scaled down while clipped, so overshoot the radius to keep the visible corner display-sized.
        val radius = transition.animateDp(
            transitionSpec = { tween(PREDICTIVE_MS / 3, easing = FastOutSlowInEasing) },
            label = "exitCorner",
        ) { if (it == EnterExitState.PostExit) maxOf(corner, MinCorner) / EXIT_SCALE else 0.dp }
        // The revealed page stays parked on the left and only shrinks along with the leaving page while the finger
        // is down; it moves to the center and grows to full size once the gesture is released. Both are driven by
        // the gesture instead of the seekable transition, which would otherwise make them follow the finger.
        val dispatcher = LocalNavigationEventDispatcherOwner.current?.navigationEventDispatcher
        val gestureState = dispatcher?.transitionState?.collectAsState()?.value
        val entering = motion.predictive && transition.targetState == EnterExitState.Visible &&
            transition.currentState == EnterExitState.PreEnter
        val progress = (gestureState as? NavigationEventTransitionState.InProgress)
            ?.latestEvent?.progress?.takeIf { entering }
        val gestureScale = progress?.let { ENTER_SCALE * lerp(1f, EXIT_SCALE, BackEasing.transform(it)) }
        val heldScale = remember { mutableFloatStateOf(1f) }
        val releaseFrom = remember { mutableFloatStateOf(1f) }
        val settle = remember { Animatable(1f) }
        if (gestureScale != null) {
            heldScale.floatValue = gestureScale
            motion.popCommitted = false
        }
        LaunchedEffect(gestureScale != null) {
            if (gestureScale != null || heldScale.floatValue == 1f) return@LaunchedEffect
            releaseFrom.floatValue = heldScale.floatValue
            settle.snapTo(0f)
            heldScale.floatValue = 1f
            if (motion.popCommitted) {
                motion.popCommitted = false
                settle.animateTo(1f, tween(RELEASE_MS, easing = EmphasizedDecelerate))
            } else {
                snapshotFlow { transition.isRunning }.first { !it }
                settle.snapTo(1f)
            }
        }

        val held = gestureScale ?: heldScale.floatValue.takeIf { it < 1f }
        // The scrim sits outside the scaled layer so it dims the whole area behind the leaving page.
        Box(
            Modifier.drawWithContent {
                drawContent()
                val alpha = SCRIM_ALPHA * (if (held != null) 1f else 1f - settle.value)
                if (alpha > 0f) drawRect(Color.Black.copy(alpha = alpha))
            },
        ) {
            Box(
                Modifier
                    .graphicsLayer {
                        val t = if (held != null) 0f else settle.value
                        val scale = held ?: lerp(releaseFrom.floatValue, 1f, t)
                        scaleX = scale
                        scaleY = scale
                        translationX = lerp(-size.width / ENTER_SHIFT_FRACTION, 0f, t)
                    }
                    .clip(RoundedCornerShape(radius.value)),
            ) { entry.Content() }
        }
    }
}
