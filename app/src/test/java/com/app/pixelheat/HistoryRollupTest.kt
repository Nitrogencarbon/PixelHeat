package com.app.pixelheat

import com.app.pixelheat.data.db.AppUsageEntity
import com.app.pixelheat.data.db.HardwareEventEntity
import com.app.pixelheat.data.db.HistoryRollup
import com.app.pixelheat.data.db.ThermalSampleEntity
import com.app.pixelheat.diagnostics.HeatModel
import com.app.pixelheat.model.AmbientSource
import com.app.pixelheat.model.AppResource
import com.app.pixelheat.model.HardwareEventType
import com.app.pixelheat.model.HeatLevel
import com.app.pixelheat.model.NetworkType
import com.app.pixelheat.model.PlugType
import com.app.pixelheat.model.ZoneSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HistoryRollupTest {

    @Test
    fun hourKeepsTheTimeWeightedTemperatureAndThePeak() {
        val rolled = HistoryRollup.sample(
            listOf(
                sample(timestamp = 1_000, weightSec = 30f, battery = 30f, max = 31f),
                sample(timestamp = 2_000, weightSec = 10f, battery = 42f, max = 48f),
            ),
        )

        assertEquals(2_000, rolled.timestamp)
        assertEquals(40f, rolled.weightSec, 0.001f)
        assertEquals(33f, rolled.batteryTemp, 0.001f)
        assertEquals(48f, rolled.maxTemp, 0.001f)
        assertEquals(0f, rolled.screenOnDuty!!, 0.001f)
    }

    @Test
    fun screenAndChargingKeepTheirShareOfTheHour() {
        val off = sample(timestamp = 1_000, weightSec = 30f, battery = 30f, screen = false, charging = false, powerW = -1f)
        val on = sample(
            timestamp = 2_000,
            weightSec = 10f,
            battery = 34f,
            screen = true,
            charging = true,
            powerW = 10f,
        )
        val rolled = HistoryRollup.sample(listOf(off, on))

        assertEquals(0.25f, rolled.screenOnDuty!!, 0.001f)
        assertEquals(0.25f, rolled.chargingDuty!!, 0.001f)
        assertEquals(false, rolled.screenOn)
        assertEquals(10f, rolled.chargePowerW!!, 0.001f)
        assertEquals(-1f, rolled.dischargePowerW!!, 0.001f)
        assertEquals(50, rolled.brightnessPct)
        assertEquals(HeatModel.charging(on) * 0.25, HeatModel.charging(rolled), 0.02)
    }

    @Test
    fun foldingARolledHourWithANewSampleKeepsTheOldShare() {
        val already = sample(timestamp = 1_000, weightSec = 10f, battery = 20f).copy(
            screenOnDuty = 1f,
            chargingDuty = 0f,
            fastChargingDuty = 0f,
            gpsDuty = 0f,
            cameraDuty = 0f,
            audioDuty = 0f,
        )
        val added = sample(timestamp = 2_000, weightSec = 10f, battery = 40f, screen = false)
        val rolled = HistoryRollup.sample(listOf(already, added))

        assertEquals(0.5f, rolled.screenOnDuty!!, 0.001f)
        assertEquals(30f, rolled.batteryTemp, 0.001f)
    }

    @Test
    fun usageSumsTimeAndAveragesCpuShare() {
        val samples = listOf(
            sample(timestamp = 1_000, weightSec = 10f),
            sample(timestamp = 2_000, weightSec = 10f),
        )
        val rows = HistoryRollup.usage(
            listOf(
                AppUsageEntity(timestamp = 1_000, resource = AppResource.FOREGROUND, packageName = "a", value = 5f),
                AppUsageEntity(timestamp = 2_000, resource = AppResource.FOREGROUND, packageName = "a", value = 7f),
                AppUsageEntity(timestamp = 1_000, resource = AppResource.CPU, packageName = "b", value = 10f),
                AppUsageEntity(timestamp = 2_000, resource = AppResource.CPU, packageName = "b", value = 30f),
            ),
            samples,
            stamp = 2_000,
        )!!

        assertEquals(12f, rows.first { it.resource == AppResource.FOREGROUND }.value, 0.001f)
        assertEquals(20f, rows.first { it.resource == AppResource.CPU }.value, 0.001f)
        assertEquals(2_000, rows.first().timestamp)
    }

    @Test
    fun usageAlreadyOnTheStampIsLeftAlone() {
        val row = AppUsageEntity(timestamp = 5, resource = AppResource.FOREGROUND, packageName = "a", value = 3f)
        assertNull(HistoryRollup.usage(listOf(row), emptyList(), stamp = 5))
    }

    @Test
    fun repeatedEventsInOneHourCollapseToTheStrongest() {
        val kept = HistoryRollup.events(
            listOf(
                HardwareEventEntity(timestamp = 1, type = HardwareEventType.DATA_BURST, active = true, value = 1f),
                HardwareEventEntity(timestamp = 2, type = HardwareEventType.DATA_BURST, active = true, value = 9f),
                HardwareEventEntity(timestamp = 3, type = HardwareEventType.DATA_BURST, active = true, value = 4f),
                HardwareEventEntity(timestamp = 4, type = HardwareEventType.DATA_BURST, active = false, value = 0f),
            ),
        )!!

        assertEquals(2, kept.size)
        assertEquals(9f, kept.first { it.active }.value)
        assertEquals(false, kept.last().active)
    }

    private fun sample(
        timestamp: Long = 0,
        weightSec: Float = 60f,
        battery: Float = 30f,
        max: Float = battery,
        screen: Boolean = false,
        charging: Boolean = false,
        powerW: Float = if (charging) 10f else -0.4f,
    ) = ThermalSampleEntity(
        timestamp = timestamp,
        weightSec = weightSec,
        heatLevel = HeatLevel.IDLE,
        batteryTemp = battery,
        cpuTemp = null,
        gpuTemp = null,
        skinTemp = null,
        modemTemp = null,
        usbTemp = null,
        maxTemp = max,
        hottestZone = "battery",
        ambientTemp = null,
        ambientSource = AmbientSource.IDLE_BASELINE,
        thermalStatus = 0,
        thermalHeadroom = null,
        batteryLevel = 80,
        currentMa = 0,
        voltageMv = 4200,
        powerW = powerW,
        isCharging = charging,
        plugType = if (charging) PlugType.AC else PlugType.NONE,
        chargerMaxW = if (charging) 27f else null,
        fastCharging = charging && powerW >= 10f,
        screenOn = screen,
        brightnessPct = if (screen) 50 else null,
        networkType = NetworkType.WIFI,
        rxKBps = 0f,
        txKBps = 0f,
        gpsActive = false,
        cameraActive = false,
        audioActive = false,
        cpuFreqPct = null,
        lux = null,
        humidity = null,
        zoneSource = ZoneSource.PUBLIC_API,
    )
}
