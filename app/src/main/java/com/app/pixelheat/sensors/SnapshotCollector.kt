package com.app.pixelheat.sensors

import android.content.Context
import android.media.AudioManager
import android.os.PowerManager
import android.provider.Settings
import com.app.pixelheat.model.DetailLevel
import com.app.pixelheat.model.EnvironmentReading
import com.app.pixelheat.model.ThermalSnapshot
import com.app.pixelheat.model.ZoneCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Builds a [ThermalSnapshot]. BASIC reads only cheap sources (sticky battery intent, power
 * manager, sysfs/HAL); FULL additionally wakes environment sensors and refreshes weather
 * from the passive location, and is used only when the phone is hot or fast charging.
 */
class SnapshotCollector(
    context: Context,
    private val batteryReader: BatteryReader,
    private val zoneReader: ThermalZoneReader,
    private val cpuReader: CpuFreqReader,
    private val specsReader: DeviceSpecsReader,
    private val networkReader: NetworkReader,
    private val environment: EnvironmentSensors,
    private val location: PassiveLocationSource,
    private val weather: WeatherClient,
    private val ambientEstimator: AmbientEstimator,
    private val tracker: HardwareStateTracker,
) {
    private val appContext = context.applicationContext
    private val powerManager = appContext.getSystemService(PowerManager::class.java)
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val mutex = Mutex()

    private var headroomAt = 0L
    private var headroom: Float? = null
    private var lastEnvAt = 0L
    private var lastEnv: EnvironmentReading? = null

    suspend fun collect(detail: DetailLevel): ThermalSnapshot = mutex.withLock {
        withContext(Dispatchers.IO) { collectLocked(detail) }
    }

    private suspend fun collectLocked(detail: DetailLevel): ThermalSnapshot {
        val now = System.currentTimeMillis()
        val battery = batteryReader.read()
        val thermal = zoneReader.read()
        val hw = tracker.state.value
        val screenOn = powerManager.isInteractive

        val env = if (detail == DetailLevel.FULL) {
            environment.read().also {
                lastEnv = it
                lastEnvAt = now
            }
        } else {
            lastEnv?.takeIf { now - lastEnvAt < ENV_REUSE_MS }
        }
        val weatherNow = if (detail == DetailLevel.FULL) {
            location.lastKnown()?.let { weather.current(it.latitude, it.longitude) } ?: weather.cached()
        } else {
            weather.cached()
        }

        val snapshot = ThermalSnapshot(
            timestamp = now,
            battery = battery,
            zones = thermal.zones,
            zoneSource = thermal.source,
            thermalStatus = powerManager.currentThermalStatus,
            headroom = readHeadroom(now),
            cpuFreqPct = cpuReader.readPercent(),
            screenOn = screenOn,
            brightnessPct = if (screenOn) readBrightness() else null,
            network = networkReader.read(),
            gpsActive = hw.gpsActive,
            cameraActive = hw.cameraActive,
            audioActive = audioManager.isMusicActive,
            environment = env,
            ambient = ambientEstimator.estimate(
                battery = battery,
                screenOn = screenOn,
                env = env,
                weather = weatherNow,
                skinC = thermal.zones.filter { it.category == ZoneCategory.SKIN }.maxOfOrNull { it.celsius },
                cpuC = thermal.zones.filter { it.category == ZoneCategory.CPU }.maxOfOrNull { it.celsius },
                gpsActive = hw.gpsActive,
                now = now,
            ),
            detail = detail,
            thresholds = thermal.thresholds,
            specs = specsReader.get(now),
            headroomThresholds = thermal.headroomThresholds,
        )
        tracker.onSample(snapshot)
        return snapshot
    }

    /** getThermalHeadroom returns NaN if polled more than about once per second. */
    private fun readHeadroom(now: Long): Float? {
        if (now - headroomAt >= HEADROOM_MIN_INTERVAL_MS) {
            headroom = powerManager.getThermalHeadroom(HEADROOM_FORECAST_S).takeIf { !it.isNaN() } ?: headroom
            headroomAt = now
        }
        return headroom
    }

    private fun readBrightness(): Int? = runCatching {
        val raw = Settings.System.getInt(appContext.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        (raw * 100 / 255).coerceIn(0, 100)
    }.getOrNull()

    private companion object {
        const val ENV_REUSE_MS = 2 * 60_000L
        const val HEADROOM_MIN_INTERVAL_MS = 1_500L
        const val HEADROOM_FORECAST_S = 10
    }
}
