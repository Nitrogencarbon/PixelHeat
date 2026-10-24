package com.app.pixelheat.diagnostics

import com.app.pixelheat.data.db.ThermalSampleEntity
import com.app.pixelheat.model.AmbientSource
import com.app.pixelheat.model.NetworkType
import com.app.pixelheat.model.PlugType
import kotlin.math.max
import kotlin.math.min

/**
 * Physical prior: estimated watts of heat each cause injects into the chassis for a sample.
 * Coefficients are typical values for a modern flagship phone.
 */
internal object HeatModel {
    private const val BASELINE_W = 0.15
    private const val GPS_W = 0.35
    private const val CAMERA_W = 2.2

    fun watts(cause: HeatCause, s: ThermalSampleEntity): Double = when (cause) {
        HeatCause.CHARGING -> charging(s)
        HeatCause.CPU_LOAD -> cpu(s)
        HeatCause.SCREEN -> screen(s)
        HeatCause.NETWORK -> network(s)
        HeatCause.GPS -> if (s.gpsActive) GPS_W else 0.0
        HeatCause.CAMERA -> if (s.cameraActive) CAMERA_W else 0.0
        HeatCause.SUNLIGHT -> s.lux?.takeIf { it >= 15_000f }?.let { min(it, 100_000f) / 100_000.0 * 3.5 } ?: 0.0
        // A hot environment isn't a source, but it reduces dissipation: express it as equivalent watts.
        HeatCause.AMBIENT -> s.ambientTemp
            ?.takeIf { s.ambientSource != AmbientSource.THERMAL_MODEL && it > 24f }
            ?.let { (it - 24.0) * 0.25 } ?: 0.0
    }

    fun charging(s: ThermalSampleEntity): Double {
        if (!s.isCharging) return 0.0
        val input = when {
            s.powerW > 0.3f -> s.powerW.toDouble()
            s.batteryLevel >= 99 -> return 0.1
            else -> s.chargerMaxW?.times(0.5) ?: 5.0
        }
        val loss = when {
            s.plugType == PlugType.WIRELESS -> 0.25
            s.fastCharging -> 0.12
            else -> 0.08
        }
        return input * loss
    }

    fun screen(s: ThermalSampleEntity): Double =
        if (s.screenOn) 0.35 + 1.1 * (s.brightnessPct ?: 50) / 100.0 else 0.0

    fun network(s: ThermalSampleEntity): Double {
        val mbps = (s.rxKBps + s.txKBps) / 1024.0
        return when (s.networkType) {
            NetworkType.CELLULAR -> if (mbps < 0.01) 0.05 else 0.3 + 1.6 * min(mbps / 2.0, 1.0)
            NetworkType.WIFI -> if (mbps < 0.01) 0.02 else 0.08 + 0.6 * min(mbps / 5.0, 1.0)
            NetworkType.ETHERNET, NetworkType.OTHER -> 0.05 + 0.3 * min(mbps / 5.0, 1.0)
            NetworkType.NONE -> 0.0
        }
    }

    /**
     * While discharging, total battery drain minus the other known consumers is the SoC load.
     * While charging the drain isn't observable, so CPU frequency or CPU-vs-battery delta is used.
     */
    fun cpu(s: ThermalSampleEntity): Double {
        if (!s.isCharging && s.powerW < 0f) {
            val others = screen(s) + network(s) + (if (s.gpsActive) GPS_W else 0.0) +
                (if (s.cameraActive) CAMERA_W else 0.0) + BASELINE_W
            return max(0.0, -s.powerW - others)
        }
        s.cpuFreqPct?.let { return it / 100.0 * 3.0 }
        val cpu = s.cpuTemp ?: return 0.0
        return ((cpu - s.batteryTemp) / 10.0 * 2.0).coerceIn(0.0, 4.0)
    }
}
