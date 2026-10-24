package com.app.pixelheat

import com.app.pixelheat.data.db.HardwareEventEntity
import com.app.pixelheat.data.db.ThermalSampleEntity
import com.app.pixelheat.diagnostics.CauseEffectAnalyzer
import com.app.pixelheat.diagnostics.DiagnosisStatus
import com.app.pixelheat.diagnostics.HeatCause
import com.app.pixelheat.model.AmbientSource
import com.app.pixelheat.model.HardwareEventType
import com.app.pixelheat.model.HeatLevel
import com.app.pixelheat.model.NetworkType
import com.app.pixelheat.model.PlugType
import com.app.pixelheat.model.ThermalStatus
import com.app.pixelheat.model.ZoneCategory
import com.app.pixelheat.model.ZoneSource
import com.app.pixelheat.sensors.ThermalZoneReader
import com.app.pixelheat.service.AdaptiveSamplingPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThermalZoneParsingTest {

    @Test
    fun parsesCurrentHalSectionAndSkipsBclTypes() {
        val dump = """
            IsStatusOverride: false
            Cached temperatures:
            	Temperature{mValue=30.0, mType=2, mName=battery, mStatus=0}
            HAL Ready: true
            HAL connection:
            Current temperatures from HAL:
            	Temperature{mValue=36.5, mType=2, mName=battery, mStatus=0}
            	Temperature{mValue=52.1, mType=0, mName=BIG, mStatus=0}
            	Temperature{mValue=41.0, mType=1, mName=G3D, mStatus=0}
            	Temperature{mValue=38.2, mType=3, mName=VIRTUAL-SKIN, mStatus=1}
            	Temperature{mValue=3800.0, mType=6, mName=vdroop, mStatus=0}
            Current cooling devices from HAL:
            	CoolingDevice{mValue=0, mType=2, mName=cpu}
        """.trimIndent()

        val zones = ThermalZoneReader.parseDumpsys(dump).associateBy { it.name }

        assertEquals(4, zones.size)
        assertEquals(36.5f, zones.getValue("battery").celsius)
        assertEquals(ZoneCategory.CPU, zones.getValue("BIG").category)
        assertEquals(ZoneCategory.SKIN, zones.getValue("VIRTUAL-SKIN").category)
        assertEquals(1, zones.getValue("VIRTUAL-SKIN").halStatus)
    }

    @Test
    fun usbUsesThePowerThermistorInsteadOfTheUiFlag() {
        val dump = """
            Current temperatures from HAL:
            	Temperature{mValue=0.0, mType=4, mName=VIRTUAL-USB-UI, mStatus=0}
            	Temperature{mValue=-12983.0, mType=-1, mName=USB-MINUS-CHARGING, mStatus=0}
            	Temperature{mValue=35.796, mType=-1, mName=usb_pwr_therm, mStatus=1}
            	Temperature{mValue=0.0, mType=-1, mName=VIRTUAL-USB-THROTTLING, mStatus=0}
        """.trimIndent()

        val usb = ThermalZoneReader.parseDumpsys(dump).filter { it.category == ZoneCategory.USB }

        assertEquals(listOf("usb_pwr_therm"), usb.map { it.name })
        assertEquals(35.796f, usb.single().celsius, 0.001f)
    }

    @Test
    fun parsesSysfsDumpInMillidegrees() {
        val zones = ThermalZoneReader.parseSysfsDump("BIG|51200\nbattery|35600\nsoc|87\nbroken|")
        assertEquals(listOf("BIG", "battery"), zones.map { it.name })
        assertEquals(51.2f, zones.first().celsius, 0.01f)
    }

    @Test
    fun parsesRealTemperatureThresholdsAndDropsHintValues() {
        val dump = """
            Temperature static thresholds from HAL:
            	TemperatureThreshold{mType=3, mName=VIRTUAL-SKIN, mHotThrottlingThresholds=[NaN, 39.0, 43.0, 45.0, 46.5, 52.0, 65.0], mColdThrottlingThresholds=[NaN, NaN, NaN, NaN, NaN, NaN, NaN]}
            	TemperatureThreshold{mType=2, mName=battery, mHotThrottlingThresholds=[NaN, NaN, NaN, NaN, NaN, NaN, 60.0], mColdThrottlingThresholds=[NaN, NaN, NaN, NaN, NaN, NaN, NaN]}
            	TemperatureThreshold{mType=0, mName=BIG, mHotThrottlingThresholds=[NaN, NaN, NaN, NaN, NaN, NaN, NaN], mColdThrottlingThresholds=[NaN, NaN, NaN, NaN, NaN, NaN, NaN]}
            	TemperatureThreshold{mType=-1, mName=VIRTUAL-USB-THROTTLING, mHotThrottlingThresholds=[NaN, NaN, NaN, NaN, 2.0, NaN, NaN], mColdThrottlingThresholds=[NaN, NaN, NaN, NaN, NaN, NaN, NaN]}
            Temperature headroom thresholds:
            [NaN, 0.8, 0.93, 1.0, 1.05, 1.23, 1.67]
        """.trimIndent()

        val limits = ThermalZoneReader.parseThresholds(dump).associateBy { it.name }
        assertEquals(setOf("VIRTUAL-SKIN", "battery"), limits.keys)
        assertEquals(ThermalStatus.LIGHT, limits.getValue("VIRTUAL-SKIN").hot.first().status)
        assertEquals(39f, limits.getValue("VIRTUAL-SKIN").hot.first().celsius)
        assertEquals(60f, limits.getValue("battery").hot.single().celsius)
        assertEquals(ZoneCategory.SKIN, limits.getValue("VIRTUAL-SKIN").category)

        val headroom = ThermalZoneReader.parseHeadroomThresholds(dump)
        assertEquals(7, headroom.size)
        assertEquals(null, headroom[0])
        assertEquals(1f, headroom[ThermalStatus.SEVERE]!!, 0.001f)
    }
}

class CauseEffectAnalyzerTest {
    private val analyzer = CauseEffectAnalyzer()

    private fun sample(
        minute: Int,
        temp: Float,
        charging: Boolean = false,
        powerW: Float = -1.5f,
        screenOn: Boolean = false,
    ) = ThermalSampleEntity(
        timestamp = minute * 60_000L,
        heatLevel = HeatLevel.WARM,
        batteryTemp = temp,
        cpuTemp = null, gpuTemp = null, skinTemp = null, modemTemp = null, usbTemp = null,
        maxTemp = temp,
        hottestZone = null,
        ambientTemp = 22f,
        ambientSource = AmbientSource.IDLE_BASELINE,
        thermalStatus = 0,
        thermalHeadroom = null,
        batteryLevel = 50,
        currentMa = 0,
        voltageMv = 4000,
        powerW = powerW,
        isCharging = charging,
        plugType = if (charging) PlugType.AC else PlugType.NONE,
        chargerMaxW = if (charging) 27f else null,
        fastCharging = charging && powerW >= 10f,
        screenOn = screenOn,
        brightnessPct = if (screenOn) 50 else null,
        networkType = NetworkType.WIFI,
        rxKBps = 0f, txKBps = 0f,
        gpsActive = false, cameraActive = false, audioActive = false,
        cpuFreqPct = null, lux = null, humidity = null,
        zoneSource = ZoneSource.PUBLIC_API,
    )

    @Test
    fun fastChargingIsIdentifiedAsPrimaryCause() {
        val samples = (0..10).map { sample(it, temp = 30f + it * 0.5f, charging = it >= 2, powerW = if (it >= 2) 20f else -0.3f) }
        val events = listOf(HardwareEventEntity(timestamp = 2 * 60_000L, type = HardwareEventType.FAST_CHARGING, active = true, value = 20f))

        val report = analyzer.analyze(samples, events)

        assertEquals(DiagnosisStatus.HEATING, report.status)
        assertEquals(HeatCause.CHARGING, report.primary?.cause)
        assertTrue(report.eventImpacts.first().significant)
    }

    @Test
    fun stableIdlePhoneIsNormal() {
        val samples = (0..10).map { sample(it * 3, temp = 30f, powerW = -0.2f) }
        assertEquals(DiagnosisStatus.NORMAL, analyzer.analyze(samples, emptyList()).status)
    }

    @Test
    fun tooFewSamplesReportsInsufficientData() {
        val report = analyzer.analyze(listOf(sample(0, 30f)), emptyList())
        assertEquals(DiagnosisStatus.INSUFFICIENT_DATA, report.status)
    }

    @Test
    fun episodesAreSegmentedAndDiagnosed() {
        val hot = (0..10).map { sample(it, temp = 38f + it * 0.4f, charging = true, powerW = 18f) }
        val cool = (11..60).map { sample(it, temp = 33f) }
        val episodes = analyzer.findEpisodes(hot + cool, emptyList())
        assertEquals(1, episodes.size)
        assertEquals(HeatCause.CHARGING, episodes.first().primaryCause?.cause)
    }
}

class SamplingPolicyTest {
    @Test
    fun cadenceFollowsAndroidThermalStatus() {
        assertEquals(HeatLevel.IDLE, AdaptiveSamplingPolicy.samplingLevelFor(ThermalStatus.NONE))
        assertEquals(HeatLevel.COLD, AdaptiveSamplingPolicy.samplingLevelFor(ThermalStatus.LIGHT))
        assertEquals(HeatLevel.WARM, AdaptiveSamplingPolicy.samplingLevelFor(ThermalStatus.MODERATE))
        assertEquals(HeatLevel.HOT, AdaptiveSamplingPolicy.samplingLevelFor(ThermalStatus.SEVERE))
        assertEquals(HeatLevel.HOT, AdaptiveSamplingPolicy.samplingLevelFor(ThermalStatus.CRITICAL))
        assertEquals(HeatLevel.HOT, AdaptiveSamplingPolicy.samplingLevelFor(ThermalStatus.EMERGENCY))
        assertEquals(HeatLevel.HOT, AdaptiveSamplingPolicy.samplingLevelFor(ThermalStatus.SHUTDOWN))
    }
}
