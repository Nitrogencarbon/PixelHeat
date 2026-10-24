package com.app.pixelheat

import com.app.pixelheat.data.db.AppUsageEntity
import com.app.pixelheat.data.db.ThermalSampleEntity
import com.app.pixelheat.diagnostics.CauseEffectAnalyzer
import com.app.pixelheat.diagnostics.HeatCause
import com.app.pixelheat.model.AmbientSource
import com.app.pixelheat.model.AppResource
import com.app.pixelheat.model.HeatLevel
import com.app.pixelheat.model.NetworkType
import com.app.pixelheat.model.PlugType
import com.app.pixelheat.model.ZoneSource
import com.app.pixelheat.sensors.AppActivityReader
import com.app.pixelheat.sensors.CameraClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppActivityTest {

    @Test
    fun cpuInfoIsGroupedByOwningApp() {
        val dump = """
            Load: 6.1 / 5.8 / 5.5
            CPU usage from 61234ms to 1234ms ago (2026-10-20 01:00:00 to 2026-10-20 01:01:00):
              24% 5123/com.android.chrome:sandboxed_process0: 20% user + 4% kernel
              11% 4990/com.android.chrome: 9% user + 2% kernel
              8.5% 1432/system_server: 5% user + 3.5% kernel
              3% 812/android.hardware.graphics.composer3-service.pixel: 2% user + 1% kernel
              0.4% 2200/com.google.android.apps.maps: 0.3% user + 0.1% kernel
            54% TOTAL: 40% user + 12% kernel + 2% iowait
        """.trimIndent()

        val usage = AppActivityReader.parseCpuInfo(dump).associate { it.packageName to it.value }

        assertEquals(35f, usage.getValue("com.android.chrome"), 0.01f)
        assertEquals(11.5f, usage.getValue(AppActivityReader.SYSTEM_PACKAGE), 0.01f)
        assertTrue("com.google.android.apps.maps" !in usage)
    }

    @Test
    fun processNamesMapToPackages() {
        assertEquals("com.android.chrome", AppActivityReader.processOwner("com.android.chrome:privileged_process1"))
        assertEquals(AppActivityReader.SYSTEM_PACKAGE, AppActivityReader.processOwner("surfaceflinger"))
        assertEquals(AppActivityReader.SYSTEM_PACKAGE, AppActivityReader.processOwner("vendor.google.wifi_ext-service-vendor"))
    }

    @Test
    fun gpsRequestersComeFromTheRealLocationDump() {
        val dump = """
            gps provider:
              last location=Location[gps 45.0,9.0 hAcc=4.0]
              listeners:
                10326/com.google.android.gms[fused_location_provider]/D917ADCA -> Request[@0 HIGH_ACCURACY, hiddenFromAppOps, WorkSource{10326 com.google.android.gms, 10574 com.google.android.apps.walletnfcrel}]
                10199/com.google.android.apps.maps/7A1 Request[@1s HIGH_ACCURACY, WorkSource{10199 com.google.android.apps.maps}]
                1000/android/AB12 Request[PASSIVE, WorkSource{10500 com.example.passive}]
            network provider:
              listeners:
                10600/com.example.other/11 Request[@60s BALANCED, WorkSource{10600 com.example.other}]
        """.trimIndent()

        assertEquals(
            setOf("com.google.android.apps.walletnfcrel", "com.google.android.apps.maps"),
            AppActivityReader.parseGpsRequesters(dump),
        )
    }

    @Test
    fun screenHeatNamesTheForegroundApp() {
        val analyzer = CauseEffectAnalyzer { if (it == "com.android.chrome") "Chrome" else CauseEffectAnalyzer.defaultLabel(it) }
        val samples = (0..10).map { sample(it, temp = 31f + it * 0.6f) }
        val apps = samples.flatMap {
            listOf(
                AppUsageEntity(timestamp = it.timestamp, resource = AppResource.FOREGROUND, packageName = "com.android.chrome", value = 50f),
                AppUsageEntity(timestamp = it.timestamp, resource = AppResource.FOREGROUND, packageName = "com.google.android.apps.maps", value = 10f),
                AppUsageEntity(timestamp = it.timestamp, resource = AppResource.CPU, packageName = "com.android.chrome", value = 30f),
            )
        }

        val report = analyzer.analyze(samples, emptyList(), apps)
        val attributed = report.causes.filter { it.apps.isNotEmpty() }

        assertTrue(attributed.isNotEmpty())
        val top = attributed.first().apps.first()
        assertEquals("Chrome", top.label)
        assertTrue(attributed.any { it.cause == HeatCause.SCREEN || it.cause == HeatCause.CPU_LOAD })
    }

    @Test
    fun appActiveDuringThePeakBeatsTheMostUsedApp() {
        val analyzer = CauseEffectAnalyzer()
        val temps = listOf(33f, 33f, 33f, 33f, 33f, 33.5f, 35f, 37f, 39f, 40f, 40f)
        val samples = temps.mapIndexed { i, t -> sample(i, t) }
        val apps = samples.flatMapIndexed { i, s ->
            buildList {
                add(AppUsageEntity(timestamp = s.timestamp, resource = AppResource.CPU, packageName = "com.example.reader", value = 20f))
                if (i >= 5) add(AppUsageEntity(timestamp = s.timestamp, resource = AppResource.CPU, packageName = "com.example.game", value = 15f))
            }
        }

        val cpu = analyzer.analyze(samples, emptyList(), apps).causes.first { it.cause == HeatCause.CPU_LOAD }

        assertEquals("com.example.game", cpu.apps.first().packageName)
        assertTrue(cpu.apps.first().peakPct >= 95f)
    }

    @Test
    fun noHeatMeansNoAppIsBlamed() {
        val samples = (0..10).map { sample(it, temp = 33f) }
        val apps = samples.map { AppUsageEntity(timestamp = it.timestamp, resource = AppResource.CPU, packageName = "com.example.game", value = 40f) }
        val report = CauseEffectAnalyzer().analyze(samples, emptyList(), apps)
        assertTrue(report.causes.all { it.apps.isEmpty() })
    }

    @Test
    fun cameraClientsFromTheRealServiceDump() {
        val dump = """
            == Service global info: ==
            Number of camera devices: 2
            Active Camera Clients:
              [
              (Camera ID: 0, Cost: 51, PID: 11946, Score: 0, State: 2User Id: 0, Client Package Name: com.google.android.GoogleCamera, Conflicting Client Devices: {2, 3, 4, })
              ]
              Allowed user IDs: 0
            == Camera service events log (most recent at top): ==
              10-20 01:45:58 : DISCONNECT device 1 client for package client.pid<1513> (PID 1513)
              10-20 01:45:58 : CONNECT device 1 client for package client.pid<1513> (PID 1513)
              10-19 23:50:14 : CONNECT device 0 client for package com.instapro.android (PID 19259)
        """.trimIndent()

        assertEquals(
            listOf(CameraClient("com.google.android.GoogleCamera", 11946)),
            AppActivityReader.parseActiveCameraClients(dump),
        )
        assertEquals(CameraClient(null, 1513), AppActivityReader.parseLastCameraConnect(dump))
        assertTrue(AppActivityReader.parseActiveCameraClients(dump.replace(Regex("""\(Camera ID[^\n]*\n"""), "")).isEmpty())
    }

    @Test
    fun nativeCameraClientIsNamed() {
        val cmdline = "/apex/com.google.android.hardware.biometrics.face/bin/android.hardware.biometrics.face-service.pixel\u0000"
        val process = AppActivityReader.processFromCmdline(cmdline)
        assertEquals("android.hardware.biometrics.face-service.pixel", process)
        assertEquals("Face Unlock", CauseEffectAnalyzer.defaultLabel(process!!))
    }

    private fun sample(minute: Int, temp: Float) = ThermalSampleEntity(
        timestamp = minute * 60_000L,
        heatLevel = HeatLevel.WARM,
        batteryTemp = temp,
        cpuTemp = temp + 15f, gpuTemp = null, skinTemp = null, modemTemp = null, usbTemp = null,
        maxTemp = temp + 15f,
        hottestZone = null,
        ambientTemp = 22f,
        ambientSource = AmbientSource.IDLE_BASELINE,
        thermalStatus = 0,
        thermalHeadroom = null,
        batteryLevel = 50,
        currentMa = 0,
        voltageMv = 4000,
        powerW = -4.5f,
        isCharging = false,
        plugType = PlugType.NONE,
        chargerMaxW = null,
        fastCharging = false,
        screenOn = true,
        brightnessPct = 90,
        networkType = NetworkType.WIFI,
        rxKBps = 0f, txKBps = 0f,
        gpsActive = false, cameraActive = false, audioActive = false,
        cpuFreqPct = 80f, lux = null, humidity = null,
        zoneSource = ZoneSource.PUBLIC_API,
    )
}
