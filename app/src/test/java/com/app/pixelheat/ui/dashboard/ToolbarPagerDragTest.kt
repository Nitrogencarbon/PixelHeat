package com.app.pixelheat.ui.dashboard

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolbarPagerDragTest {

    @Test
    fun dragAcrossOneTabMovesOnePage() {
        assertEquals(1f, dragPagePosition(tabs(), start = 0f, totalDx = 110f), 0.01f)
    }

    @Test
    fun dragAcrossUnequalTabsAccumulates() {
        assertEquals(1.5f, dragPagePosition(tabs(), start = 0f, totalDx = 160f), 0.01f)
    }

    @Test
    fun dragPastEitherEndStops() {
        assertEquals(2f, dragPagePosition(tabs(), start = 2f, totalDx = 80f), 0.01f)
        assertEquals(0f, dragPagePosition(tabs(), start = 0f, totalDx = -40f), 0.01f)
    }

    @Test
    fun dragLeftFromTheEndUsesThePreviousGap() {
        assertEquals(1f, dragPagePosition(tabs(), start = 2f, totalDx = -100f), 0.01f)
        assertEquals(1f - 50f / 110f, dragPagePosition(tabs(), start = 2f, totalDx = -150f), 0.01f)
    }

    @Test
    fun dragInARightToLeftRowFollowsTheFinger() {
        val moved = dragPagePosition(tabsRightToLeft(), start = 0f, totalDx = -100f)
        assertEquals(1f, moved, 0.01f)
    }

    @Test
    fun flingAdvancesOnePageAndASlowReleaseSnaps() {
        assertEquals(1, settlePage(position = 0.2f, velocityPx = 2000f, minFlingPx = 200f, lastIndex = 2))
        assertEquals(0, settlePage(position = 0.2f, velocityPx = -2000f, minFlingPx = 200f, lastIndex = 2))
        assertEquals(0, settlePage(position = 0.2f, velocityPx = 0f, minFlingPx = 200f, lastIndex = 2))
        assertEquals(1, settlePage(position = 0.6f, velocityPx = 0f, minFlingPx = 200f, lastIndex = 2))
        assertEquals(2, settlePage(position = 2f, velocityPx = 2000f, minFlingPx = 200f, lastIndex = 2))
    }

    private fun tabs(): List<Rect> = listOf(
        Rect(0f, 0f, 100f, 40f),
        Rect(100f, 0f, 220f, 40f),
        Rect(220f, 0f, 300f, 40f),
    )

    private fun tabsRightToLeft(): List<Rect> = listOf(
        Rect(220f, 0f, 300f, 40f),
        Rect(100f, 0f, 220f, 40f),
        Rect(0f, 0f, 100f, 40f),
    )
}
