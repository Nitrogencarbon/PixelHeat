package com.app.pixelheat.data.db

import com.app.pixelheat.model.HardwareEventType
import com.app.pixelheat.model.NetworkType
import com.app.pixelheat.model.ThermalStatus
import com.app.pixelheat.util.formatPower
import com.app.pixelheat.util.formatRate
import kotlin.math.roundToInt

/** Human-readable sentence for an event, derived from its type, direction and optional value. */
fun HardwareEventEntity.describe(): String {
    val v = value
    return when (type) {
        HardwareEventType.SCREEN -> if (active) "Screen turned on" else "Screen turned off"
        HardwareEventType.BRIGHTNESS -> when {
            active -> "Brightness raised" + (v?.let { " to ${it.roundToInt()}%" }.orEmpty())
            else -> "Brightness lowered" + (v?.let { " to ${it.roundToInt()}%" }.orEmpty())
        }
        HardwareEventType.GPS -> if (active) "GPS started by ${appName ?: "an app"}" else "GPS stopped"
        HardwareEventType.CAMERA -> if (active) "Camera opened" + (appName?.let { " by $it" }.orEmpty()) else "Camera closed"
        HardwareEventType.NETWORK -> {
            val network = v?.toInt()?.let { NetworkType.entries.getOrNull(it) }
            when {
                network != null && network != NetworkType.NONE -> "Switched to ${network.label}"
                active -> "Network connected"
                else -> "Network disconnected"
            }
        }
        HardwareEventType.DATA_BURST ->
            if (active) "Heavy data traffic" + (v?.let { " · ${formatRate(it)}" }.orEmpty()) else "Data traffic back to normal"
        HardwareEventType.CHARGING ->
            if (active) "Charging started" + (v?.let { " · charger up to ${formatPower(it)}" }.orEmpty()) else "Charger unplugged"
        HardwareEventType.FAST_CHARGING ->
            if (active) "Fast charging" + (v?.let { " at ${formatPower(it)}" }.orEmpty()) else "Fast charging ended"
        HardwareEventType.THERMAL -> {
            val status = v?.toInt()?.let { ThermalStatus.label(it).lowercase() }
            when {
                status != null && active -> "Thermal status rose to $status"
                status != null -> "Thermal status dropped to $status"
                active -> "Thermal status rose"
                else -> "Thermal status dropped"
            }
        }
        HardwareEventType.SUNLIGHT ->
            if (active) "Direct sunlight" + (v?.let { " · ${it.roundToInt()} lux" }.orEmpty()) else "Out of direct sunlight"
    }
}
