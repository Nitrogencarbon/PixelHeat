package com.app.pixelheat

import com.app.pixelheat.data.db.ThermalSampleEntity
import com.app.pixelheat.data.db.referenceTemp
import com.app.pixelheat.model.AmbientSource
import com.app.pixelheat.model.HeatLevel
import com.app.pixelheat.model.NetworkType
import com.app.pixelheat.model.PhoneTempSource
import com.app.pixelheat.model.PlugType
import com.app.pixelheat.model.ZoneSource
import com.app.pixelheat.model.phoneTempSource
import com.app.pixelheat.model.phoneTemperature
import com.app.pixelheat.ui.chart.ChartBuilder
import com.app.pixelheat.ui.chart.ChartWindow
import com.app.pixelheat.ui.chart.SeriesKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneScoreTest {

    @Test
    fun skinReplacesBatteryWhenItCanBeRead() {
        assertEquals(33f, phoneTemperature(33f, 36f))
        assertEquals(PhoneTempSource.SKIN, phoneTempSource(33f))
        assertEquals(36f, phoneTemperature(null, 36f))
        assertEquals(PhoneTempSource.BATTERY, phoneTempSource(null))
        assertEquals(36f, phoneTemperature(Float.NaN, 36f))
    }

    @Test
    fun storedSampleUsesTheSameScore() {
        val withSkin = sample(battery = 36f, skin = 33f, cpu = 55f)
        val batteryOnly = sample(battery = 36f, skin = null, cpu = null)
        assertEquals(33f, withSkin.referenceTemp)
        assertEquals(36f, batteryOnly.referenceTemp)
    }

    @Test
    fun historyDefaultsToSkinOnAFixedOperatingRange() {
        val now = 1_800_000L
        val point = sample(battery = 36f, skin = 33f, cpu = 55f).copy(timestamp = now - 60_000L, ambientTemp = 8f)
        val data = ChartBuilder.build(listOf(point), emptyList(), ChartWindow.H1, now, { null })

        assertEquals(listOf(SeriesKey.SKIN), data.series.map { it.key })
        assertTrue(SeriesKey.CPU in data.available)
        assertEquals(0f, data.minY)
        assertEquals(100f, data.maxY)
    }

    @Test
    fun historyFallsBackToBatteryWithoutSkin() {
        val now = 1_800_000L
        val point = sample(battery = 36f, skin = null, cpu = null).copy(timestamp = now - 60_000L)
        val data = ChartBuilder.build(listOf(point), emptyList(), ChartWindow.H1, now, { null })
        assertEquals(listOf(SeriesKey.BATTERY), data.series.map { it.key })
    }

    @Test
    fun axisWidensForReadingsBelowZero() {
        val now = 1_800_000L
        val point = sample(battery = 20f, skin = null, cpu = null).copy(timestamp = now - 60_000L, ambientTemp = -6f)
        val data = ChartBuilder.build(listOf(point), emptyList(), ChartWindow.H1, now, { null })
        assertEquals(-10f, data.minY)
    }

    private fun sample(battery: Float, skin: Float?, cpu: Float?) = ThermalSampleEntity(
        timestamp = 0L,
        heatLevel = HeatLevel.IDLE,
        batteryTemp = battery,
        cpuTemp = cpu,
        gpuTemp = null,
        skinTemp = skin,
        modemTemp = null,
        usbTemp = null,
        maxTemp = maxOf(battery, skin ?: battery, cpu ?: battery),
        hottestZone = null,
        ambientTemp = 22f,
        ambientSource = AmbientSource.IDLE_BASELINE,
        thermalStatus = 0,
        thermalHeadroom = null,
        batteryLevel = 80,
        currentMa = -200,
        voltageMv = 4200,
        powerW = -0.8f,
        isCharging = false,
        plugType = PlugType.NONE,
        chargerMaxW = null,
        fastCharging = false,
        screenOn = true,
        brightnessPct = 40,
        networkType = NetworkType.WIFI,
        rxKBps = 0f,
        txKBps = 0f,
        gpsActive = false,
        cameraActive = false,
        audioActive = false,
        cpuFreqPct = null,
        lux = null,
        humidity = null,
        zoneSource = if (skin == null) ZoneSource.PUBLIC_API else ZoneSource.HAL_PRIVILEGED,
    )
}
