package com.app.pixelheat.sensors

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import com.app.pixelheat.model.BatteryInfo
import com.app.pixelheat.model.PlugType
import kotlin.math.abs

class BatteryReader(private val context: Context) {
    private val batteryManager = context.getSystemService(BatteryManager::class.java)

    /** Some kernels report CURRENT_NOW with the opposite sign of the documented convention. */
    @Volatile
    private var signInverted = false

    /** Reads the sticky ACTION_BATTERY_CHANGED intent: no receiver registration, no wakeup. */
    fun read(): BatteryInfo {
        val intent = ContextCompat.registerReceiver(
            context,
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        return parse(intent)
    }

    fun parse(intent: Intent?): BatteryInfo {
        val tempRaw = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val voltageRaw = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
            ?: BatteryManager.BATTERY_STATUS_UNKNOWN
        val health = intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN)
            ?: BatteryManager.BATTERY_HEALTH_UNKNOWN
        val cycles = intent?.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1) ?: -1
        val maxCurrentUa = intent?.getIntExtra(EXTRA_MAX_CHARGING_CURRENT, -1) ?: -1
        val maxVoltageUv = intent?.getIntExtra(EXTRA_MAX_CHARGING_VOLTAGE, -1) ?: -1

        val plugType = when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> PlugType.AC
            BatteryManager.BATTERY_PLUGGED_USB -> PlugType.USB
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> PlugType.WIRELESS
            BatteryManager.BATTERY_PLUGGED_DOCK -> PlugType.DOCK
            else -> PlugType.NONE
        }
        val charging = plugType != PlugType.NONE &&
            (status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)

        val voltageMv = if (voltageRaw in 1..99) voltageRaw * 1000 else voltageRaw
        val chargerMaxW = if (plugType != PlugType.NONE && maxCurrentUa > 0) {
            val volts = if (maxVoltageUv > 0) maxVoltageUv / 1_000_000f else 5f
            maxCurrentUa / 1_000_000f * volts
        } else {
            null
        }

        var currentMa = readCurrentMa()
        if (status == BatteryManager.BATTERY_STATUS_CHARGING && currentMa < -50) signInverted = true
        if (signInverted) currentMa = -currentMa

        return BatteryInfo(
            tempC = if (tempRaw == Int.MIN_VALUE) Float.NaN else tempRaw / 10f,
            levelPct = if (level >= 0 && scale > 0) level * 100 / scale else -1,
            voltageMv = voltageMv,
            currentMa = currentMa,
            isCharging = charging,
            plugType = plugType,
            chargerMaxW = chargerMaxW,
            health = health,
            cycleCount = cycles.takeIf { it >= 0 },
        )
    }

    private fun readCurrentMa(): Int {
        val raw = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        if (raw == Int.MIN_VALUE || raw == 0) return 0
        // Spec says µA, but some devices report mA directly.
        return if (abs(raw) >= 10_000) raw / 1000 else raw
    }

    companion object {
        private const val EXTRA_MAX_CHARGING_CURRENT = "max_charging_current"
        private const val EXTRA_MAX_CHARGING_VOLTAGE = "max_charging_voltage"

        fun healthLabel(health: Int): String = when (health) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheated"
            BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over voltage"
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Failure"
            BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
            else -> "Unknown"
        }
    }
}
