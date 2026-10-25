package com.app.pixelheat.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PredictiveBackMotionTest {

    private val metrics = BackMetrics(
        widthPx = 1080f,
        heightPx = 2400f,
        marginPx = 24f,
        enteringOffsetPx = 288f,
        cornerRadiusPx = 0f,
        maxScrimAlpha = MAX_SCRIM_ALPHA_DARK,
    )

    @Test
    fun gestureCurveStartsFastAndReachesTheEnds() {
        assertEquals(0f, backGesture(0f), 0.0001f)
        assertEquals(1f, backGesture(1f), 0.0001f)
        assertTrue(backGesture(0.2f) > 0.2f)
        var previous = 0f
        var step = 0.05f
        while (step < 1f) {
            val value = backGesture(step)
            assertTrue(value >= previous)
            previous = value
            step += 0.05f
        }
    }

    @Test
    fun emphasizedPathIsContinuousAndMonotonic() {
        assertEquals(0f, emphasized(0f), 0.0001f)
        assertEquals(1f, emphasized(1f), 0.0001f)
        val atSplit = emphasized(0.166666f)
        assertEquals(0.4f, atSplit, 0.02f)
        var previous = 0f
        var step = 0.02f
        while (step < 1f) {
            val value = emphasized(step)
            assertTrue("$step went backwards", value + 0.001f >= previous)
            previous = value
            step += 0.02f
        }
    }

    @Test
    fun decelerateMatchesThePlatformFactor() {
        assertEquals(0f, decelerate(0f), 0.0001f)
        assertEquals(0.75f, decelerate(0.5f), 0.0001f)
        assertEquals(1f, decelerate(1f), 0.0001f)
    }

    @Test
    fun leftEdgePinsTheClosingSurfaceToTheMargin() {
        val frame = previewFrame(1f, touchY = 0f, initialTouchY = 0f, EDGE_LEFT, metrics)
        assertEquals(metrics.widthPx * MAX_SCALE, frame.closing.width, 0.5f)
        assertEquals(metrics.widthPx - metrics.marginPx, frame.closing.right, 0.5f)
        assertEquals(MAX_SCRIM_ALPHA_DARK, frame.scrimAlpha, 0.0001f)
        assertEquals(1f, frame.closingAlpha, 0.0001f)
    }

    @Test
    fun rightEdgeKeepsTheClosingSurfaceCentered() {
        val frame = previewFrame(1f, 0f, 0f, EDGE_RIGHT, metrics)
        val side = (metrics.widthPx - frame.closing.width) / 2f
        assertEquals(side, frame.closing.left, 0.5f)
    }

    @Test
    fun verticalShiftStopsAtTheMargin() {
        val parked = previewFrame(1f, touchY = 0f, initialTouchY = 0f, EDGE_LEFT, metrics)
        val limit = (metrics.heightPx - parked.closing.height) / 2f - metrics.marginPx
        val down = previewFrame(1f, touchY = 10_000f, initialTouchY = 0f, EDGE_LEFT, metrics)
        val up = previewFrame(1f, touchY = -10_000f, initialTouchY = 0f, EDGE_LEFT, metrics)
        assertEquals(limit, down.closing.top - parked.closing.top, 0.5f)
        assertEquals(-limit, up.closing.top - parked.closing.top, 0.5f)
        assertTrue(abs(down.closing.top - parked.closing.top) <= limit + 0.5f)
    }

    @Test
    fun previousSurfaceLeadsInFromTheLeftAtNinetyFivePercent() {
        val frame = previewFrame(0f, 0f, 0f, EDGE_LEFT, metrics)
        assertEquals(metrics.widthPx * INITIAL_ENTERING_SCALE, frame.entering.width, 0.5f)
        assertTrue(frame.entering.left < 0f)
    }

    @Test
    fun commitFadesTheClosingSurfaceOutInTheFirstFifth() {
        val start = previewFrame(1f, 0f, 0f, EDGE_LEFT, metrics)
        val early = postCommitFrame(start.closing, start.entering, metrics, linear = 0.1f, flingScale = 1f)
        val gone = postCommitFrame(start.closing, start.entering, metrics, linear = 0.2f, flingScale = 1f)
        val end = postCommitFrame(start.closing, start.entering, metrics, linear = 1f, flingScale = 1f)
        assertEquals(0.5f, early.closingAlpha, 0.0001f)
        assertEquals(0f, gone.closingAlpha, 0.0001f)
        assertEquals(0f, end.scrimAlpha, 0.0001f)
        assertEquals(metrics.widthPx, end.entering.width, 0.5f)
        assertTrue(end.closing.left > start.closing.left)
    }

    @Test
    fun flingVelocityMatchesTheShellSpringScale() {
        assertEquals(-1.2f, flingVisualVelocity(0f, gestureProgress = 0f, edgeFactor = 2f), 0.0001f)
        assertEquals(-10f, flingVisualVelocity(100f, gestureProgress = 0.5f, edgeFactor = 2f), 0.0001f)
        assertEquals(0f, flingVisualVelocity(-5f, gestureProgress = 0.5f, edgeFactor = 2f), 0.0001f)
        assertEquals(1f, edgeVelocityFactor(EDGE_NONE), 0.0001f)
        assertEquals(2f, edgeVelocityFactor(EDGE_LEFT), 0.0001f)
    }

    private companion object {
        const val EDGE_NONE = 2
    }
}
