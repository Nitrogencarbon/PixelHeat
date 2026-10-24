package com.app.pixelheat.data

import com.app.pixelheat.data.db.AppUsageEntity
import com.app.pixelheat.data.db.HardwareEventEntity
import com.app.pixelheat.model.AppUsage
import com.app.pixelheat.data.db.LifetimeStatsEntity
import com.app.pixelheat.data.db.ThermalDao
import com.app.pixelheat.data.db.ThermalSampleEntity
import com.app.pixelheat.model.HardwareEventType
import com.app.pixelheat.model.HeatLevel
import com.app.pixelheat.model.LifetimeAverages
import com.app.pixelheat.model.ThermalSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ThermalRepository(private val dao: ThermalDao) {

    suspend fun record(snapshot: ThermalSnapshot, level: HeatLevel): ThermalSampleEntity =
        dao.insertSampleWithStats(snapshot.toEntity(level))

    suspend fun recordEvent(
        type: HardwareEventType,
        active: Boolean,
        value: Float?,
        timestamp: Long = System.currentTimeMillis(),
        packageName: String? = null,
        appName: String? = null,
    ) {
        dao.insertEvent(
            HardwareEventEntity(
                timestamp = timestamp,
                type = type,
                active = active,
                value = value,
                packageName = packageName,
                appName = appName,
            ),
        )
    }

    suspend fun recordAppUsage(timestamp: Long, usage: List<AppUsage>) {
        if (usage.isEmpty()) return
        dao.insertAppUsage(usage.map { AppUsageEntity(timestamp = timestamp, resource = it.resource, packageName = it.packageName, value = it.value) })
    }

    fun observeAppUsage(since: Long): Flow<List<AppUsageEntity>> = dao.observeAppUsageSince(since)

    suspend fun appUsageBetween(from: Long, to: Long) = dao.appUsageBetween(from, to)

    fun observeSamples(since: Long): Flow<List<ThermalSampleEntity>> = dao.observeSamplesSince(since)

    fun observeEvents(since: Long): Flow<List<HardwareEventEntity>> = dao.observeEventsSince(since)

    suspend fun samplesBetween(from: Long, to: Long) = dao.samplesBetween(from, to)

    suspend fun eventsBetween(from: Long, to: Long) = dao.eventsBetween(from, to)

    fun observeLifetime(installedAt: Long): Flow<LifetimeAverages?> =
        dao.observeLifetime().map { it?.toAverages(installedAt) }

    suspend fun prune(olderThan: Long) {
        dao.pruneSamples(olderThan)
        dao.pruneEvents(olderThan)
        dao.pruneAppUsage(olderThan)
    }
}

fun ThermalSnapshot.toEntity(level: HeatLevel): ThermalSampleEntity = ThermalSampleEntity(
    timestamp = timestamp,
    heatLevel = level,
    batteryTemp = battery.tempC,
    cpuTemp = cpuTemp,
    gpuTemp = gpuTemp,
    skinTemp = skinTemp,
    modemTemp = modemTemp,
    usbTemp = usbTemp,
    maxTemp = maxTemp,
    hottestZone = hottestZone?.name,
    ambientTemp = ambient.celsius,
    ambientSource = ambient.source,
    thermalStatus = thermalStatus,
    thermalHeadroom = headroom,
    batteryLevel = battery.levelPct,
    currentMa = battery.currentMa,
    voltageMv = battery.voltageMv,
    powerW = battery.powerW,
    isCharging = battery.isCharging,
    plugType = battery.plugType,
    chargerMaxW = battery.chargerMaxW,
    fastCharging = battery.fastCharging,
    screenOn = screenOn,
    brightnessPct = brightnessPct,
    networkType = network.type,
    rxKBps = network.rxKBps,
    txKBps = network.txKBps,
    gpsActive = gpsActive,
    cameraActive = cameraActive,
    audioActive = audioActive,
    cpuFreqPct = cpuFreqPct,
    lux = environment?.lux,
    humidity = environment?.humidityPct,
    zoneSource = zoneSource,
    wifiDbm = network.wifiDbm,
    cellDbm = network.cellDbm,
    cellKBps = network.cellKBps,
    wifiKBps = network.wifiKBps,
)

private fun LifetimeStatsEntity.toAverages(installedAt: Long): LifetimeAverages {
    fun pct(part: Double) = if (totalSec > 0) (part / totalSec * 100).toFloat() else 0f
    fun avg(sum: Double, sec: Double) = if (sec > 0) (sum / sec).toFloat() else null
    return LifetimeAverages(
        since = minOf(installedAt, firstSampleAt),
        monitoredHours = totalSec / 3600.0,
        samples = sampleCount,
        avgPhoneTemp = avg(referenceTempSum, totalSec),
        avgMaxTemp = avg(maxTempSum, totalSec),
        avgAmbient = avg(ambientSum, ambientSec),
        peakTemp = peakTemp,
        peakAt = peakAt,
        chargingPct = pct(chargingSec),
        hotPct = pct(hotSec),
        throttledPct = pct(throttledSec),
        screenOnPct = pct(screenOnSec),
        avgDischargeW = avg(dischargeEnergyWs, dischargeSec),
    )
}
