package com.app.pixelheat.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.app.pixelheat.model.AmbientSource
import com.app.pixelheat.model.AppResource
import com.app.pixelheat.model.HardwareEventType
import com.app.pixelheat.model.HeatLevel
import com.app.pixelheat.model.NetworkType
import com.app.pixelheat.model.PhoneTempSource
import com.app.pixelheat.model.PlugType
import com.app.pixelheat.model.ThermalStatus
import com.app.pixelheat.model.ZoneSource
import com.app.pixelheat.model.phoneTempSource
import com.app.pixelheat.model.phoneTemperature

@Entity(tableName = "thermal_samples", indices = [Index("timestamp")])
data class ThermalSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    /** Seconds of observation this sample represents (time since the previous stored sample). */
    val weightSec: Float = 0f,
    val heatLevel: HeatLevel,
    val batteryTemp: Float,
    val cpuTemp: Float?,
    val gpuTemp: Float?,
    val skinTemp: Float?,
    val modemTemp: Float?,
    val usbTemp: Float?,
    val maxTemp: Float,
    val hottestZone: String?,
    val ambientTemp: Float?,
    val ambientSource: AmbientSource,
    val thermalStatus: Int,
    val thermalHeadroom: Float?,
    val batteryLevel: Int,
    val currentMa: Int,
    val voltageMv: Int,
    val powerW: Float,
    val isCharging: Boolean,
    val plugType: PlugType,
    val chargerMaxW: Float?,
    val fastCharging: Boolean,
    val screenOn: Boolean,
    val brightnessPct: Int?,
    val networkType: NetworkType,
    val rxKBps: Float,
    val txKBps: Float,
    val gpsActive: Boolean,
    val cameraActive: Boolean,
    val audioActive: Boolean,
    val cpuFreqPct: Float?,
    val lux: Float?,
    val humidity: Float?,
    val zoneSource: ZoneSource,
    /** Wi-Fi RSSI of the associated network, dBm. */
    val wifiDbm: Int? = null,
    /** Serving-cell signal, RSRP when the radio reports it, otherwise RSSI, dBm. */
    val cellDbm: Int? = null,
    val cellKBps: Float? = null,
    val wifiKBps: Float? = null,
)

/** Phone score for this sample: skin when it was readable, otherwise battery. */
val ThermalSampleEntity.referenceTemp: Float get() = phoneTemperature(skinTemp, batteryTemp)

val ThermalSampleEntity.referenceSource: PhoneTempSource get() = phoneTempSource(skinTemp)

@Entity(tableName = "hardware_events", indices = [Index("timestamp")])
data class HardwareEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val type: HardwareEventType,
    /** true = the component turned on / condition started. */
    val active: Boolean,
    /**
     * Optional magnitude, interpreted per type: brightness %, lux, watts (charging), KB/s (data burst),
     * [ThermalStatus] level, or the [NetworkType] ordinal of the new network.
     */
    val value: Float? = null,
    /** App or native process that triggered the event (camera client, GPS requester), when known. */
    val packageName: String? = null,
    /** Display name of [packageName], resolved when the event was recorded. */
    val appName: String? = null,
)

/** Per-app resource use recorded alongside the thermal sample with the same [timestamp]. */
@Entity(tableName = "app_usage", indices = [Index("timestamp")])
data class AppUsageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val resource: AppResource,
    val packageName: String,
    val value: Float,
)

@Entity(tableName = "lifetime_stats")
data class LifetimeStatsEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val firstSampleAt: Long,
    val lastSampleAt: Long,
    val sampleCount: Long = 0,
    val totalSec: Double = 0.0,
    val batteryTempSum: Double = 0.0,
    /** Time-weighted sum of [ThermalSampleEntity.referenceTemp]. */
    @ColumnInfo(defaultValue = "0.0")
    val referenceTempSum: Double = 0.0,
    val maxTempSum: Double = 0.0,
    val ambientSum: Double = 0.0,
    val ambientSec: Double = 0.0,
    val peakTemp: Float? = null,
    val peakAt: Long? = null,
    val chargingSec: Double = 0.0,
    val hotSec: Double = 0.0,
    val throttledSec: Double = 0.0,
    val screenOnSec: Double = 0.0,
    val dischargeEnergyWs: Double = 0.0,
    val dischargeSec: Double = 0.0,
) {
    fun accumulate(s: ThermalSampleEntity): LifetimeStatsEntity {
        val w = s.weightSec.toDouble()
        val ref = s.referenceTemp
        val newPeak = peakTemp == null || ref > peakTemp
        val discharging = !s.isCharging
        return copy(
            lastSampleAt = s.timestamp,
            sampleCount = sampleCount + 1,
            totalSec = totalSec + w,
            batteryTempSum = batteryTempSum + s.batteryTemp * w,
            referenceTempSum = referenceTempSum + ref * w,
            maxTempSum = maxTempSum + s.maxTemp * w,
            ambientSum = ambientSum + (s.ambientTemp?.let { it * w } ?: 0.0),
            ambientSec = ambientSec + if (s.ambientTemp != null) w else 0.0,
            peakTemp = if (newPeak) ref else peakTemp,
            peakAt = if (newPeak) s.timestamp else peakAt,
            chargingSec = chargingSec + if (s.isCharging) w else 0.0,
            hotSec = hotSec + if (ref >= HOT_THRESHOLD_C) w else 0.0,
            throttledSec = throttledSec + if (s.thermalStatus >= ThermalStatus.MODERATE) w else 0.0,
            screenOnSec = screenOnSec + if (s.screenOn) w else 0.0,
            dischargeEnergyWs = dischargeEnergyWs + if (discharging && s.powerW < 0f) -s.powerW * w else 0.0,
            dischargeSec = dischargeSec + if (discharging) w else 0.0,
        )
    }

    companion object {
        const val SINGLETON_ID = 1
        const val HOT_THRESHOLD_C = 40f
    }
}
