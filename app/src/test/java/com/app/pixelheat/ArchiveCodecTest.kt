package com.app.pixelheat

import com.app.pixelheat.data.archive.ArchiveCodec
import com.app.pixelheat.data.archive.ArchiveContents
import com.app.pixelheat.data.archive.ArchiveFormatException
import com.app.pixelheat.data.db.AppUsageEntity
import com.app.pixelheat.data.db.HardwareEventEntity
import com.app.pixelheat.data.db.LifetimeStatsEntity
import com.app.pixelheat.data.db.ThermalSampleEntity
import com.app.pixelheat.data.prefs.CollectedEstimate
import com.app.pixelheat.model.AmbientSource
import com.app.pixelheat.model.AppResource
import com.app.pixelheat.model.HardwareEventType
import com.app.pixelheat.model.HeatLevel
import com.app.pixelheat.model.NetworkType
import com.app.pixelheat.model.PlugType
import com.app.pixelheat.model.ZoneSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ArchiveCodecTest {

    @Test
    fun roundTripKeepsMeasurementsAndDropsMissingNumbers() {
        val original = ArchiveContents(
            exportedAt = 99L,
            samples = listOf(
                ThermalSampleEntity(
                    timestamp = 1_000L,
                    weightSec = 12f,
                    heatLevel = HeatLevel.WARM,
                    batteryTemp = 36f,
                    cpuTemp = null,
                    gpuTemp = null,
                    skinTemp = 33f,
                    modemTemp = null,
                    usbTemp = null,
                    maxTemp = 40f,
                    hottestZone = "BIG",
                    ambientTemp = null,
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
                    rxKBps = 1f,
                    txKBps = 2f,
                    gpsActive = false,
                    cameraActive = true,
                    audioActive = false,
                    cpuFreqPct = null,
                    lux = null,
                    humidity = null,
                    zoneSource = ZoneSource.HAL_PRIVILEGED,
                    wifiDbm = -70,
                ),
            ),
            events = listOf(
                HardwareEventEntity(
                    timestamp = 1_000L,
                    type = HardwareEventType.CAMERA,
                    active = true,
                    appName = "Face Unlock",
                    packageName = "android.hardware.biometrics.face-service.pixel",
                ),
            ),
            appUsage = listOf(AppUsageEntity(timestamp = 1_000L, resource = AppResource.CAMERA, packageName = "android", value = 1f)),
            lifetime = LifetimeStatsEntity(
                firstSampleAt = 1,
                lastSampleAt = 2,
                sampleCount = 3,
                referenceTempSum = 10.0,
                inUseTempSum = 12.5,
                inUseSec = 4.0,
            ),
            estimate = CollectedEstimate(
                idleBaselineTemp = Float.NaN,
                idleBaselineAt = 0,
                fusionX = 21f,
                fusionP = 0.4f,
                fusionAt = 5,
                fusionRth = 2.5f,
                fusionSelfHeat = 0.8f,
                ambientBiasC = -5f,
                weatherTemp = 14f,
                weatherAt = 6,
                weatherLat = 45.1f,
                weatherLon = 9.2f,
                weatherShortwave = Float.NaN,
                weatherWindMs = 1.5f,
                weatherIsDay = true,
            ),
        )

        val back = ArchiveCodec.decode(ArchiveCodec.encode(original))

        assertEquals(original.samples, back.samples)
        assertEquals(original.events, back.events)
        assertEquals(original.appUsage, back.appUsage)
        assertEquals(original.lifetime, back.lifetime)
        assertEquals(true, back.estimate.idleBaselineTemp.isNaN())
        assertEquals(21f, back.estimate.fusionX)
        assertEquals(true, back.estimate.weatherIsDay)
    }

    @Test
    fun aBackupWithoutInUseTotalsFillsThemFromSamples() {
        val original = ArchiveContents(
            exportedAt = 1L,
            samples = listOf(
                ThermalSampleEntity(
                    timestamp = 1_000L,
                    weightSec = 12f,
                    heatLevel = HeatLevel.WARM,
                    batteryTemp = 40f,
                    cpuTemp = null,
                    gpuTemp = null,
                    skinTemp = 33f,
                    modemTemp = null,
                    usbTemp = null,
                    maxTemp = 40f,
                    hottestZone = null,
                    ambientTemp = null,
                    ambientSource = AmbientSource.IDLE_BASELINE,
                    thermalStatus = 0,
                    thermalHeadroom = null,
                    batteryLevel = 80,
                    currentMa = 0,
                    voltageMv = 4000,
                    powerW = -0.2f,
                    isCharging = false,
                    plugType = PlugType.NONE,
                    chargerMaxW = null,
                    fastCharging = false,
                    screenOn = true,
                    brightnessPct = null,
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
                ),
            ),
            events = emptyList(),
            appUsage = emptyList(),
            lifetime = LifetimeStatsEntity(firstSampleAt = 1, lastSampleAt = 2, sampleCount = 1),
            estimate = CollectedEstimate(
                idleBaselineTemp = Float.NaN,
                idleBaselineAt = 0,
                fusionX = Float.NaN,
                fusionP = Float.NaN,
                fusionAt = 0,
                fusionRth = 2.5f,
                fusionSelfHeat = 0.8f,
                ambientBiasC = -5f,
                weatherTemp = Float.NaN,
                weatherAt = 0,
                weatherLat = Float.NaN,
                weatherLon = Float.NaN,
                weatherShortwave = Float.NaN,
                weatherWindMs = Float.NaN,
                weatherIsDay = null,
            ),
        )

        val back = ArchiveCodec.decode(ArchiveCodec.encode(original)).lifetime!!

        assertEquals(33.0 * 12.0, back.inUseTempSum, 0.001)
        assertEquals(12.0, back.inUseSec, 0.001)
    }

    @Test
    fun aNewerFormatIsRefused() {
        val text = """{"format":2,"exportedAt":1,"samples":[]}"""
        assertThrows(ArchiveFormatException::class.java) { ArchiveCodec.decode(text) }
    }

    @Test
    fun aRandomFileIsRefused() {
        assertThrows(ArchiveFormatException::class.java) { ArchiveCodec.decode("not a backup") }
    }
}
