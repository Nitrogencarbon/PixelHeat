package com.app.pixelheat.ui

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * In-app predictive back, matched to the Android 17 cross-activity animation
 * (`DefaultCrossActivityBackAnimation` / `CrossActivityBackAnimation` on android17-release).
 *
 * Pre-commit follows the finger: [BackGestureInterpolator] is `PathInterpolator(0.1, 0.1, 0, 1)`,
 * the closing surface scales to [MAX_SCALE] and keeps an 8dp edge margin, and the vertical shift
 * uses [DecelerateInterpolator] so the surface never crosses that margin. The previous surface
 * starts at [INITIAL_ENTERING_SCALE] (or tighter when the window corner radius eats into it) and
 * is pulled in from the left. A black scrim sits between them at 0.8 in night mode and 0.2 in day
 * mode for the whole gesture.
 *
 * Post-commit is a separate 450ms playhead. Rects use the system emphasized path
 * (two cubics: (0.05, 0) → (0.133333, 0.06) → (0.166666, 0.4), then (0.208333, 0.82) → (0.25, 1) → (1, 1)).
 * The closing surface fades out over the first fifth of that playhead. A fling spring
 * (`STIFFNESS_LOW` 200, `DAMPING_RATIO_LOW_BOUNCY` 0.75, value scaled by 100) shrinks both surfaces
 * and settles back; it is not a Material motion-scheme spring. Those scheme springs would not
 * match the system gesture.
 *
 * The 8dp margin is `cross_task_back_vertical_margin`. The 96dp lead-in is
 * `cross_activity_back_entering_start_offset`.
 *
 * Design decision: the system optionally crops the task surface above the navigation bar. This
 * layer is already the app content under the system bars, so that crop is not applied here.
 */
internal const val MAX_SCALE = 0.85f
internal const val INITIAL_ENTERING_SCALE = 0.95f
internal const val POST_COMMIT_MS = 450

/** Upper bound on holding the settled back pose while NavDisplay drops the closing screen. */
internal const val HANDOFF_TIMEOUT_MS = 500L
internal const val MAX_SCRIM_ALPHA_DARK = 0.8f
internal const val MAX_SCRIM_ALPHA_LIGHT = 0.2f
internal const val SPRING_SCALE = 100f
internal const val MAX_FLING_VELOCITY = 1000f
internal const val DEFAULT_FLING_VELOCITY = 120f

internal const val EDGE_LEFT = 0
internal const val EDGE_RIGHT = 1

internal data class BackRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun scaleCentered(scale: Float): BackRect {
        val px = (left + right) / 2f
        val py = (top + bottom) / 2f
        return BackRect(
            px + (left - px) * scale,
            py + (top - py) * scale,
            px + (right - px) * scale,
            py + (bottom - py) * scale,
        )
    }

    fun offset(dx: Float, dy: Float) = BackRect(left + dx, top + dy, right + dx, bottom + dy)

    fun lerp(other: BackRect, t: Float) = BackRect(
        left + (other.left - left) * t,
        top + (other.top - top) * t,
        right + (other.right - right) * t,
        bottom + (other.bottom - bottom) * t,
    )
}

internal data class BackMetrics(
    val widthPx: Float,
    val heightPx: Float,
    val marginPx: Float,
    val enteringOffsetPx: Float,
    val cornerRadiusPx: Float,
    val maxScrimAlpha: Float,
)

internal data class BackFrame(
    val closing: BackRect,
    val entering: BackRect,
    val fullWidth: Float,
    val closingAlpha: Float,
    val scrimAlpha: Float,
    val cornerRadiusPx: Float,
)

internal fun backGesture(progress: Float): Float =
    unitBezierY(0.1f, 0.1f, 0f, 1f, progress.coerceIn(0f, 1f))

/** System emphasized path used for the post-commit rects. */
internal fun emphasized(linear: Float): Float {
    val t = linear.coerceIn(0f, 1f)
    val splitX = 0.166666f
    return if (t <= splitX) {
        bezierY(0f, 0f, 0.05f, 0f, 0.133333f, 0.06f, splitX, 0.4f, t)
    } else {
        bezierY(splitX, 0.4f, 0.208333f, 0.82f, 0.25f, 1f, 1f, 1f, t)
    }
}

internal fun decelerate(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return 1f - (1f - x) * (1f - x)
}

/**
 * Extra scale velocity for the post-commit spring, in visual-scale units per second.
 * Negative, so the surfaces dip below their current size and the spring returns them to 1.
 */
internal fun flingVisualVelocity(progressPerSecond: Float, gestureProgress: Float, edgeFactor: Float): Float {
    var start = progressPerSecond * SPRING_SCALE * (1f - MAX_SCALE) * edgeFactor
    if (gestureProgress < 0.1f) start = max(start, DEFAULT_FLING_VELOCITY)
    start = start.coerceIn(0f, MAX_FLING_VELOCITY)
    return -start / SPRING_SCALE
}

internal fun edgeVelocityFactor(swipeEdge: Int): Float =
    if (swipeEdge == EDGE_LEFT || swipeEdge == EDGE_RIGHT) 2f else 1f

internal fun previewFrame(
    rawProgress: Float,
    touchY: Float,
    initialTouchY: Float,
    swipeEdge: Int,
    metrics: BackMetrics,
): BackFrame {
    val progress = backGesture(rawProgress)
    val full = BackRect(0f, 0f, metrics.widthPx, metrics.heightPx)
    var closing = full.lerp(closingTarget(full, metrics.marginPx, swipeEdge), progress)
    val y = yOffset(touchY, initialTouchY, full.height, closing, metrics.marginPx)
    closing = closing.offset(0f, y)
    var entering = enteringStart(full, metrics).lerp(enteringTarget(full, metrics), progress)
    entering = entering.offset(0f, y)
    return BackFrame(
        closing = closing,
        entering = entering,
        fullWidth = full.width,
        closingAlpha = 1f,
        scrimAlpha = metrics.maxScrimAlpha,
        cornerRadiusPx = metrics.cornerRadiusPx,
    )
}

internal fun postCommitFrame(
    closingAtRelease: BackRect,
    enteringAtRelease: BackRect,
    metrics: BackMetrics,
    linear: Float,
    flingScale: Float,
): BackFrame {
    val t = emphasized(linear)
    val full = BackRect(0f, 0f, metrics.widthPx, metrics.heightPx)
    val closingEnd = full.offset(closingAtRelease.left + metrics.enteringOffsetPx, 0f)
    val scale = flingScale.coerceIn(0.01f, 1f)
    return BackFrame(
        closing = closingAtRelease.lerp(closingEnd, t).scaleCentered(scale),
        entering = enteringAtRelease.lerp(full, t).scaleCentered(scale),
        fullWidth = full.width,
        closingAlpha = (1f - linear.coerceIn(0f, 1f) * 5f).coerceAtLeast(0f),
        scrimAlpha = metrics.maxScrimAlpha * (1f - linear.coerceIn(0f, 1f)),
        cornerRadiusPx = metrics.cornerRadiusPx,
    )
}

internal class ProgressVelocityTracker {
    private val times = ArrayDeque<Long>()
    private val values = ArrayDeque<Float>()

    fun add(timeMillis: Long, progress: Float) {
        if (times.isNotEmpty() && timeMillis <= times.last()) return
        times.add(timeMillis)
        values.add(progress)
        while (times.size > 1 && timeMillis - times.first() > 100L) {
            times.removeFirst()
            values.removeFirst()
        }
    }

    /** Interpolated-progress units per second over the last 100ms. */
    fun velocity(): Float {
        if (times.size < 2) return 0f
        val dt = (times.last() - times.first()) / 1000f
        if (dt <= 0.008f) return 0f
        return (values.last() - values.first()) / dt
    }

    fun reset() {
        times.clear()
        values.clear()
    }
}

private fun closingTarget(full: BackRect, marginPx: Float, swipeEdge: Int): BackRect {
    var target = full.scaleCentered(MAX_SCALE)
    if (swipeEdge != EDGE_RIGHT) {
        target = target.offset(full.right - target.right - marginPx, 0f)
    }
    return target
}

private fun enteringStart(full: BackRect, metrics: BackMetrics): BackRect {
    val nonRoundedHeight = full.height - 2f * metrics.cornerRadiusPx
    val startScale = min(INITIAL_ENTERING_SCALE, nonRoundedHeight / full.height)
    val scaled = full.scaleCentered(startScale)
    val lead = max(scaled.width * (1f - MAX_SCALE), metrics.enteringOffsetPx)
    return scaled.offset(-lead, 0f)
}

private fun enteringTarget(full: BackRect, metrics: BackMetrics): BackRect =
    enteringStart(full, metrics).scaleCentered(MAX_SCALE)

private fun yOffset(
    touchY: Float,
    initialTouchY: Float,
    screenHeight: Float,
    rect: BackRect,
    marginPx: Float,
): Float {
    val raw = touchY - initialTouchY
    val direction = if (raw < 0f) -1f else 1f
    val span = screenHeight / 2f
    if (span <= 0f) return 0f
    val ratio = min(span, abs(raw)) / span
    return max(0f, (screenHeight - rect.height) / 2f - marginPx) * decelerate(ratio) * direction
}

private fun unitBezierY(x1: Float, y1: Float, x2: Float, y2: Float, x: Float): Float =
    bezierY(0f, 0f, x1, y1, x2, y2, 1f, 1f, x)

private fun bezierY(
    x0: Float,
    y0: Float,
    x1: Float,
    y1: Float,
    x2: Float,
    y2: Float,
    x3: Float,
    y3: Float,
    x: Float,
): Float {
    if (x <= x0) return y0
    if (x >= x3) return y3
    var lo = 0f
    var hi = 1f
    repeat(24) {
        val mid = (lo + hi) / 2f
        if (cubic(x0, x1, x2, x3, mid) < x) lo = mid else hi = mid
    }
    return cubic(y0, y1, y2, y3, (lo + hi) / 2f)
}

private fun cubic(p0: Float, p1: Float, p2: Float, p3: Float, u: Float): Float {
    val t = 1f - u
    return t * t * t * p0 + 3f * t * t * u * p1 + 3f * t * u * u * p2 + u * u * u * p3
}
