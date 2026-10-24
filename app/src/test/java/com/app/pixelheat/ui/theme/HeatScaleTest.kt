package com.app.pixelheat.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeatScaleTest {

    @Test
    fun cpuAt40IsStillColdAndBarelyFillsTheBar() {
        assertEquals(0, CpuHeat.band(40f))
        assertTrue(CpuHeat.fraction(40f)!! < 0.2f)
    }

    @Test
    fun batteryAt40IsWarm() {
        assertEquals(2, BatteryHeat.band(40f))
    }

    @Test
    fun cpuOnlyTurnsHotNearThrottling() {
        assertEquals(1, CpuHeat.band(55f))
        assertEquals(2, CpuHeat.band(70f))
        assertEquals(3, CpuHeat.band(85f))
        assertEquals(4, CpuHeat.band(98f))
    }

    @Test
    fun ambientUsesAComfortScale() {
        assertEquals(0, AmbientHeat.band(5f))
        assertEquals(1, AmbientHeat.band(21f))
        assertEquals(2, AmbientHeat.band(30f))
    }
}
