package com.app.pixelheat

import com.app.pixelheat.data.db.LifetimeStatsEntity
import com.app.pixelheat.data.db.ThermalSampleEntity
import com.app.pixelheat.data.db.isInUse
import com.app.pixelheat.model.AmbientSource
import com.app.pixelheat.model.HeatLevel
import com.app.pixelheat.model.NetworkType
import com.app.pixelheat.model.PlugType
import com.app.pixelheat.model.ZoneSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InUseStatsTest {

    @Test
    fun screenOnChargingLoadAndRadiosCountAsInUse() {
        assertTrue(sample(screenOn = true).isInUse())
        assertTrue(sample(charging = true).isInUse())
        assertTrue(sample(powerW = -0.8f).isInUse())
        assertTrue(sample(powerW = 1.2f).isInUse())
        assertTrue(sample(gps = true).isInUse())
        assertTrue(sample(camera = true).isInUse())
        assertTrue(sample(audio = true).isInUse())
        assertFalse(sample().isInUse())
    }

    @Test
    fun accumulateUsesSkinAndSkipsRest() {
        val base = LifetimeStatsEntity(firstSampleAt = 0, lastSampleAt = 0)
        val awake = base.accumulate(sample(screenOn = true, battery = 40f, skin = 33f, weightSec = 10f))
        val both = awake.accumulate(sample(weightSec = 20f, battery = 30f))

        assertEquals(330.0, both.inUseTempSum, 0.001)
        assertEquals(10.0, both.inUseSec, 0.001)
        assertEquals(30.0, both.totalSec, 0.001)
    }

    @Test
    fun backfillFillsMissingTotalsAndKeepsStoredOnes() {
        val awake = sample(screenOn = true, battery = 36f, weightSec = 12f)
        val rest = sample(weightSec = 8f, battery = 28f)
        val empty = LifetimeStatsEntity(firstSampleAt = 1, lastSampleAt = 2)
        val filled = empty.backfillInUse(listOf(awake, rest))
        assertEquals(36.0 * 12.0, filled.inUseTempSum, 0.001)
        assertEquals(12.0, filled.inUseSec, 0.001)

        val stored = empty.copy(inUseTempSum = 1.0, inUseSec = 1.0)
        assertEquals(stored, stored.backfillInUse(listOf(awake)))
    }

    private fun sample(
        screenOn: Boolean = false,
        charging: Boolean = false,
        powerW: Float = -0.2f,
        gps: Boolean = false,
        camera: Boolean = false,
        audio: Boolean = false,
        battery: Float = 30f,
        skin: Float? = null,
        weightSec: Float = 0f,
    ) = ThermalSampleEntity(
        timestamp = 1_000L,
        weightSec = weightSec,
        heatLevel = HeatLevel.WARM,
        batteryTemp = battery,
        cpuTemp = null,
        gpuTemp = null,
        skinTemp = skin,
        modemTemp = null,
        usbTemp = null,
        maxTemp = battery,
        hottestZone = null,
        ambientTemp = null,
        ambientSource = AmbientSource.IDLE_BASELINE,
        thermalStatus = 0,
        thermalHeadroom = null,
        batteryLevel = 80,
        currentMa = 0,
        voltageMv = 4000,
        powerW = powerW,
        isCharging = charging,
        plugType = if (charging) PlugType.AC else PlugType.NONE,
        chargerMaxW = null,
        fastCharging = false,
        screenOn = screenOn,
        brightnessPct = null,
        networkType = NetworkType.WIFI,
        rxKBps = 0f,
        txKBps = 0f,
        gpsActive = gps,
        cameraActive = camera,
        audioActive = audio,
        cpuFreqPct = null,
        lux = null,
        humidity = null,
        zoneSource = ZoneSource.PUBLIC_API,
    )
}
