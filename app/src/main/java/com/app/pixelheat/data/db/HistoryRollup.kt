package com.app.pixelheat.data.db

import com.app.pixelheat.model.AppResource
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Folds detail older than [DETAIL_RETENTION_MS] into one row per hour.
 *
 * Temperatures, power and data rates become time-weighted averages. Screen, charging, GPS, camera
 * and audio keep the fraction of the hour they were on, so a later average is not a coin flip
 * between on and off. The peak temperature of the hour is kept. An hour that already holds a single
 * sample is left as it was.
 */
internal object HistoryRollup {
    const val HOUR_MS = 60L * 60 * 1000
    const val DETAIL_RETENTION_MS = 30L * 24 * 60 * 60_000

    /** One sample that stands for [samples]. Requires at least two rows. */
    fun sample(samples: List<ThermalSampleEntity>): ThermalSampleEntity {
        require(samples.size >= 2)
        val useWeight = samples.any { it.weightSec > 0f }
        fun weight(s: ThermalSampleEntity) = if (useWeight) s.weightSec.toDouble() else 1.0
        fun weighted(select: (ThermalSampleEntity) -> Float?): Float? {
            var sum = 0.0
            var w = 0.0
            for (s in samples) {
                val value = select(s) ?: continue
                val sw = weight(s)
                if (sw <= 0.0) continue
                sum += value * sw
                w += sw
            }
            return if (w > 0.0) (sum / w).toFloat() else null
        }
        fun duty(explicit: (ThermalSampleEntity) -> Float?, active: (ThermalSampleEntity) -> Boolean): Float {
            var on = 0.0
            var w = 0.0
            for (s in samples) {
                val sw = weight(s)
                if (sw <= 0.0) continue
                on += s.onFraction(explicit(s), active(s)) * sw
                w += sw
            }
            return if (w > 0.0) (on / w).toFloat() else 0f
        }
        fun <T> mode(portion: (ThermalSampleEntity) -> Double, value: (ThermalSampleEntity) -> T): T {
            val totals = LinkedHashMap<T, Double>()
            for (s in samples) {
                val w = portion(s)
                if (w <= 0.0) continue
                totals.merge(value(s), w, Double::plus)
            }
            return totals.maxByOrNull { it.value }?.key ?: value(samples.last())
        }

        val totalWeight = samples.sumOf { weight(it) }.toFloat()
        val screenDuty = duty({ it.screenOnDuty }, { it.screenOn })
        val chargingDuty = duty({ it.chargingDuty }, { it.isCharging })
        val fastDuty = duty({ it.fastChargingDuty }, { it.fastCharging })
        val gpsDuty = duty({ it.gpsDuty }, { it.gpsActive })
        val cameraDuty = duty({ it.cameraDuty }, { it.cameraActive })
        val audioDuty = duty({ it.audioDuty }, { it.audioActive })
        val peak = samples.maxBy { it.maxTemp }

        return samples.last().copy(
            id = 0,
            timestamp = samples.maxOf { it.timestamp },
            weightSec = totalWeight,
            heatLevel = samples.maxBy { it.heatLevel.ordinal }.heatLevel,
            batteryTemp = weighted { it.batteryTemp } ?: samples.last().batteryTemp,
            cpuTemp = weighted { it.cpuTemp },
            gpuTemp = weighted { it.gpuTemp },
            skinTemp = weighted { it.skinTemp },
            modemTemp = weighted { it.modemTemp },
            usbTemp = weighted { it.usbTemp },
            maxTemp = peak.maxTemp,
            hottestZone = peak.hottestZone,
            ambientTemp = weighted { it.ambientTemp },
            ambientSource = mode({ weight(it) }, { it.ambientSource }),
            thermalStatus = mode({ weight(it) }, { it.thermalStatus }),
            thermalHeadroom = weighted { it.thermalHeadroom },
            batteryLevel = weighted { it.batteryLevel.toFloat() }?.roundToInt() ?: samples.last().batteryLevel,
            currentMa = weighted { it.currentMa.toFloat() }?.roundToInt() ?: samples.last().currentMa,
            voltageMv = weighted { it.voltageMv.toFloat() }?.roundToInt() ?: samples.last().voltageMv,
            powerW = weighted { it.powerW } ?: samples.last().powerW,
            isCharging = chargingDuty >= 0.5f,
            plugType = mode({ weight(it) * it.onFraction(it.chargingDuty, it.isCharging) }, { it.plugType }),
            chargerMaxW = chargingAverage(samples, ::weight) { it.chargerMaxW },
            fastCharging = fastDuty >= 0.5f,
            screenOn = screenDuty >= 0.5f,
            brightnessPct = brightness(samples, ::weight),
            networkType = mode({ weight(it) }, { it.networkType }),
            rxKBps = weighted { it.rxKBps } ?: 0f,
            txKBps = weighted { it.txKBps } ?: 0f,
            gpsActive = gpsDuty >= 0.5f,
            cameraActive = cameraDuty >= 0.5f,
            audioActive = audioDuty >= 0.5f,
            cpuFreqPct = weighted { it.cpuFreqPct },
            lux = weighted { it.lux },
            humidity = weighted { it.humidity },
            zoneSource = mode({ weight(it) }, { it.zoneSource }),
            wifiDbm = weighted { it.wifiDbm?.toFloat() }?.roundToInt(),
            cellDbm = weighted { it.cellDbm?.toFloat() }?.roundToInt(),
            cellKBps = weighted { it.cellKBps },
            wifiKBps = weighted { it.wifiKBps },
            screenOnDuty = screenDuty,
            chargingDuty = chargingDuty,
            fastChargingDuty = fastDuty,
            gpsDuty = gpsDuty,
            cameraDuty = cameraDuty,
            audioDuty = audioDuty,
            chargePowerW = portionPower(samples, ::weight, charging = true),
            dischargePowerW = portionPower(samples, ::weight, charging = false),
        )
    }

    /**
     * Sums amounts (foreground time, traffic) and averages shares (CPU, GPS, camera) onto [stamp].
     * Null when every row is already a single total at that timestamp.
     */
    fun usage(
        rows: List<AppUsageEntity>,
        samples: List<ThermalSampleEntity>,
        stamp: Long,
    ): List<AppUsageEntity>? {
        if (rows.isEmpty()) return null
        val oneEach = rows.groupBy { it.resource to it.packageName }.values.all { it.size == 1 }
        if (oneEach && rows.all { it.timestamp == stamp }) return null
        val weightAt = HashMap<Long, Double>()
        for (s in samples) weightAt.merge(s.timestamp, s.weightSec.toDouble().coerceAtLeast(0.0), Double::plus)
        data class Acc(var amount: Double = 0.0, var share: Double = 0.0, var shareWeight: Double = 0.0)
        val acc = LinkedHashMap<Pair<AppResource, String>, Acc>()
        for (row in rows) {
            val bucket = acc.getOrPut(row.resource to row.packageName) { Acc() }
            when (row.resource) {
                AppResource.FOREGROUND, AppResource.MOBILE_DATA, AppResource.WIFI_DATA ->
                    bucket.amount += row.value
                AppResource.CPU, AppResource.GPS, AppResource.CAMERA -> {
                    val w = weightAt[row.timestamp]?.takeIf { it > 0.0 } ?: 1.0
                    bucket.share += row.value * w
                    bucket.shareWeight += w
                }
            }
        }
        return acc.mapNotNull { (key, bucket) ->
            val value = when (key.first) {
                AppResource.CPU, AppResource.GPS, AppResource.CAMERA ->
                    if (bucket.shareWeight > 0.0) (bucket.share / bucket.shareWeight).toFloat() else 0f
                else -> bucket.amount.toFloat()
            }
            if (value <= 0f) null
            else AppUsageEntity(timestamp = stamp, resource = key.first, packageName = key.second, value = value)
        }
    }

    /**
     * Keeps one event per type and on/off state: the strongest value, or the earliest when they tie.
     * Null when the hour is already that thin.
     */
    fun events(rows: List<HardwareEventEntity>): List<HardwareEventEntity>? {
        if (rows.isEmpty()) return null
        val kept = rows.groupBy { it.type to it.active }.map { (_, group) ->
            group.maxWith(compareBy<HardwareEventEntity> { abs(it.value ?: 0f) }.thenBy { -it.timestamp })
        }
        if (kept.size == rows.size) return null
        return kept.map { it.copy(id = 0) }.sortedBy { it.timestamp }
    }

    private fun brightness(
        samples: List<ThermalSampleEntity>,
        weight: (ThermalSampleEntity) -> Double,
    ): Int? {
        var sum = 0.0
        var w = 0.0
        for (s in samples) {
            val value = s.brightnessPct ?: continue
            val sw = weight(s) * s.onFraction(s.screenOnDuty, s.screenOn)
            if (sw <= 0.0) continue
            sum += value * sw
            w += sw
        }
        return if (w > 0.0) (sum / w).roundToInt() else null
    }

    private fun chargingAverage(
        samples: List<ThermalSampleEntity>,
        weight: (ThermalSampleEntity) -> Double,
        value: (ThermalSampleEntity) -> Float?,
    ): Float? {
        var sum = 0.0
        var w = 0.0
        for (s in samples) {
            val reading = value(s) ?: continue
            val sw = weight(s) * s.onFraction(s.chargingDuty, s.isCharging)
            if (sw <= 0.0) continue
            sum += reading * sw
            w += sw
        }
        return if (w > 0.0) (sum / w).toFloat() else null
    }

    /** Average power over the charging or discharging part of each sample. */
    private fun portionPower(
        samples: List<ThermalSampleEntity>,
        weight: (ThermalSampleEntity) -> Double,
        charging: Boolean,
    ): Float? {
        var sum = 0.0
        var w = 0.0
        for (s in samples) {
            val fraction = if (charging) {
                s.onFraction(s.chargingDuty, s.isCharging).toDouble()
            } else {
                1.0 - s.onFraction(s.chargingDuty, s.isCharging)
            }
            val sw = weight(s) * fraction
            if (sw <= 0.0) continue
            val power = when {
                charging -> s.chargePowerW ?: s.powerW
                else -> s.dischargePowerW ?: s.powerW
            }
            sum += power * sw
            w += sw
        }
        return if (w > 0.0) (sum / w).toFloat() else null
    }
}
