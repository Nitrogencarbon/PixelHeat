package com.app.pixelheat

import com.app.pixelheat.data.db.AppUsageEntity
import com.app.pixelheat.data.db.ThermalSampleEntity
import com.app.pixelheat.diagnostics.AppHeatRanker
import com.app.pixelheat.diagnostics.HeatCause
import com.app.pixelheat.diagnostics.HeatModel
import com.app.pixelheat.model.AmbientSource
import com.app.pixelheat.model.AppResource
import com.app.pixelheat.model.HeatLevel
import com.app.pixelheat.model.NetworkType
import com.app.pixelheat.model.PlugType
import com.app.pixelheat.model.ZoneSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppHeatRankerTest {

    @Test
    fun foregroundTimeSplitsDisplayHeatAcrossTheWholeWindow() {
        val samples = listOf(
            sample(atSec = 60, weightSec = 60f, screenOn = true),
            sample(atSec = 120, weightSec = 60f, screenOn = true),
        )
        val apps = listOf(
            usage(60, AppResource.FOREGROUND, "maps", 40f),
            usage(60, AppResource.FOREGROUND, "reader", 20f),
            usage(120, AppResource.FOREGROUND, "maps", 60f),
        )

        val ranking = AppHeatRanker.rank(samples, apps, from = 0, to = 120_000, labelOf = { it })

        assertEquals(listOf("maps", "reader"), ranking.apps.map { it.packageName })
        assertEquals(83f, ranking.apps[0].sharePct, 1f)
        assertEquals(17f, ranking.apps[1].sharePct, 1f)
        assertEquals("Display", ranking.apps[0].sources)
        val screenW = HeatModel.watts(HeatCause.SCREEN, samples[0])
        assertEquals(screenW * 100.0 / 120.0, ranking.apps[0].avgWatts.toDouble(), 0.02)
    }

    @Test
    fun cpuSharesAreUsedInsteadOfForegroundTime() {
        val samples = listOf(sample(atSec = 60, weightSec = 60f, powerW = -2f))
        val apps = listOf(
            usage(60, AppResource.CPU, "game", 70f),
            usage(60, AppResource.CPU, "sync", 30f),
            usage(60, AppResource.FOREGROUND, "launcher", 60f),
        )

        val ranking = AppHeatRanker.rank(samples, apps, from = 0, to = 60_000)

        assertEquals(listOf("game", "sync"), ranking.apps.map { it.packageName })
        assertEquals(70f, ranking.apps[0].sharePct, 1f)
        assertTrue(ranking.apps.none { it.packageName == "launcher" })
    }

    @Test
    fun timeBeforeTheWindowIsNotCounted() {
        val samples = listOf(
            sample(atSec = 60, weightSec = 60f, screenOn = true),
            sample(atSec = 120, weightSec = 60f, screenOn = true),
        )
        val apps = listOf(
            usage(60, AppResource.FOREGROUND, "early", 60f),
            usage(120, AppResource.FOREGROUND, "later", 60f),
        )

        val ranking = AppHeatRanker.rank(samples, apps, from = 30_000, to = 120_000)

        assertEquals("later", ranking.apps[0].packageName)
        assertEquals(67f, ranking.apps[0].sharePct, 1f)
        assertEquals(33f, ranking.apps[1].sharePct, 1f)
    }

    private fun usage(atSec: Int, resource: AppResource, pkg: String, value: Float) = AppUsageEntity(
        timestamp = atSec * 1000L,
        resource = resource,
        packageName = pkg,
        value = value,
    )

    private fun sample(
        atSec: Int,
        weightSec: Float,
        screenOn: Boolean = false,
        powerW: Float = -0.2f,
    ) = ThermalSampleEntity(
        timestamp = atSec * 1000L,
        weightSec = weightSec,
        heatLevel = HeatLevel.WARM,
        batteryTemp = 32f,
        cpuTemp = null,
        gpuTemp = null,
        skinTemp = null,
        modemTemp = null,
        usbTemp = null,
        maxTemp = 32f,
        hottestZone = null,
        ambientTemp = 22f,
        ambientSource = AmbientSource.IDLE_BASELINE,
        thermalStatus = 0,
        thermalHeadroom = null,
        batteryLevel = 80,
        currentMa = 0,
        voltageMv = 4000,
        powerW = powerW,
        isCharging = false,
        plugType = PlugType.NONE,
        chargerMaxW = null,
        fastCharging = false,
        screenOn = screenOn,
        brightnessPct = if (screenOn) 50 else null,
        networkType = NetworkType.NONE,
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
