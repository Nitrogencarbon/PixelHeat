package com.app.pixelheat.ui.detail

import com.app.pixelheat.model.DetailLevel
import com.app.pixelheat.model.HeatLevel
import com.app.pixelheat.model.MonitorStatus
import com.app.pixelheat.model.PrivilegeState
import com.app.pixelheat.model.ThermalSnapshot
import com.app.pixelheat.model.ThermalStatus
import com.app.pixelheat.model.ZoneCategory
import com.app.pixelheat.model.ZoneThreshold
import com.app.pixelheat.sensors.BatteryReader
import com.app.pixelheat.util.fmt
import com.app.pixelheat.util.formatDuration
import com.app.pixelheat.util.formatPower
import com.app.pixelheat.util.formatRate
import com.app.pixelheat.util.formatTemp
import com.app.pixelheat.util.formatSignedTemp
import com.app.pixelheat.util.formatTime
import kotlin.math.roundToInt

private fun yesNo(b: Boolean) = if (b) "Yes" else "No"

/** Live key/value facts shown in a sensor's detail page. */
fun detailRows(
    metric: Metric,
    s: ThermalSnapshot,
    monitor: MonitorStatus?,
    privilege: PrivilegeState,
): List<Pair<String, String>> {
    val b = s.battery
    val zonesOf = metric.zoneCategory?.let { c -> s.zones.filter { it.category == c } }.orEmpty()
    val zoneRows = if (metric.zoneCategory != null) {
        listOf(
            "Zones in this group" to "${zonesOf.size}",
            "Coolest zone" to (zonesOf.minByOrNull { it.celsius }?.let { "${it.name} · ${formatTemp(it.celsius)}" } ?: "—"),
            "Data source" to s.zoneSource.label,
        )
    } else {
        emptyList()
    }
    return when (metric) {
        Metric.BATTERY_TEMP -> listOf(
            "Temperature" to formatTemp(b.tempC),
            "Health" to BatteryReader.healthLabel(b.health),
            "Charge cycles" to (b.cycleCount?.toString() ?: "—"),
            "Level" to "${b.levelPct}%",
            "State" to if (b.isCharging) "Charging · ${b.plugType.label}" else "Discharging",
            "Voltage" to "${(b.voltageMv / 1000f).fmt(3)} V",
            "Current" to "${b.currentMa} mA",
        ) + batterySpecRows(s) + limitRows(s.thresholds, ZoneCategory.BATTERY)
        Metric.SKIN -> zoneRows + limitRows(s.thresholds, ZoneCategory.SKIN)
        Metric.GPU -> zoneRows + gpuSpecRows(s) + limitRows(s.thresholds, ZoneCategory.GPU)
        Metric.MODEM -> zoneRows + limitRows(s.thresholds, ZoneCategory.MODEM)
        Metric.USB -> zoneRows + limitRows(s.thresholds, ZoneCategory.USB)
        Metric.CPU -> listOf("Average frequency" to (s.cpuFreqPct?.let { "${it.roundToInt()}% of max" } ?: "—")) +
            cpuSpecRows(s) + zoneRows + limitRows(s.thresholds, ZoneCategory.CPU)
        Metric.HOTTEST -> {
            val z = s.hottestZone
            listOf(
                "Zone" to (z?.name ?: "—"),
                "Group" to (z?.category?.label ?: "—"),
                "Temperature" to formatTemp(z?.celsius),
                "Zones read" to "${s.zones.size}",
            )
        }
        Metric.AMBIENT -> buildList {
            add("Estimate" to formatTemp(s.ambient.celsius))
            s.ambient.rawCelsius?.let { add("Before correction" to formatTemp(it)) }
            if (s.ambient.biasC != 0f) add("Correction" to formatSignedTemp(s.ambient.biasC))
            s.ambient.sigma?.let { add("Uncertainty" to "±${it.fmt(1)} °C") }
            add("Method" to s.ambient.source.label)
            s.ambient.outdoorLikelihood?.let { add("Outdoors likelihood" to "${(it * 100).roundToInt()}%") }
            if (s.ambient.pocket) add("Placement" to "Pocket or covered")
            s.ambient.contributions.sortedByDescending { it.weight }.take(4).forEach {
                add(it.label to "${formatTemp(it.celsius)} · ${(it.weight * 100).roundToInt()}%")
            }
            add("Ambient sensor" to formatTemp(s.environment?.ambientSensorC))
            add("Humidity" to (s.environment?.humidityPct?.let { "${it.roundToInt()}%" } ?: "—"))
            add("Light" to (s.environment?.lux?.let { "${it.roundToInt()} lux" } ?: "—"))
        }
        Metric.THERMAL_STATUS -> listOf(
            "Status" to ThermalStatus.label(s.thermalStatus),
            "Throttling" to yesNo(s.thermalStatus >= ThermalStatus.MODERATE),
            "Headroom" to (s.headroom?.fmt(2) ?: "Unavailable"),
        ) + headroomLimitRows(s.headroomThresholds)
        Metric.CHARGE -> listOf(
            "Level" to "${b.levelPct}%",
            "State" to if (b.isCharging) "Charging" else "Discharging",
            "Charger" to b.plugType.label,
            "Charger rating" to (b.chargerMaxW?.let { formatPower(it) } ?: "—"),
            "Fast charging" to yesNo(b.fastCharging),
        ) + chargingLimitRows(s.thresholds)
        Metric.POWER -> listOf(
            "Power" to formatPower(b.powerW),
            "Direction" to if (b.powerW >= 0) "Into the battery" else "Out of the battery",
            "Current" to "${b.currentMa} mA",
            "Voltage" to "${(b.voltageMv / 1000f).fmt(3)} V",
        )
        Metric.SCREEN -> listOf(
            "State" to if (s.screenOn) "On" else "Off",
            "Brightness" to (s.brightnessPct?.let { "$it%" } ?: "—"),
        )
        Metric.NETWORK -> listOf(
            "Connection" to s.network.type.label,
            "Wi-Fi" to formatDbm(s.network.wifiDbm),
            "Cellular" to formatDbm(s.network.cellDbm),
            "Wi-Fi traffic" to formatRate(s.network.wifiKBps),
            "Mobile traffic" to formatRate(s.network.cellKBps),
            "Download" to formatRate(s.network.rxKBps),
            "Upload" to formatRate(s.network.txKBps),
            "Total" to formatRate(s.network.totalKBps),
        )
        Metric.GPS -> listOf("State" to if (s.gpsActive) "Active" else "Idle", "Detection" to "GNSS status callbacks")
        Metric.CAMERA -> listOf("State" to if (s.cameraActive) "In use" else "Idle")
        Metric.AUDIO -> listOf("State" to if (s.audioActive) "Playing" else "Silent")
        Metric.LIGHT -> listOf(
            "Illuminance" to (s.environment?.lux?.let { "${it.roundToInt()} lux" } ?: "—"),
            "Likely in sunlight" to yesNo((s.environment?.lux ?: 0f) >= 25_000f),
            "Humidity" to (s.environment?.humidityPct?.let { "${it.roundToInt()}%" } ?: "—"),
        )
        Metric.SOURCE -> listOf(
            "Source" to s.zoneSource.label,
            "Zones read" to "${s.zones.size}",
            "Privileged access" to (privilege.active?.label ?: "None"),
            "Root" to privilege.root.label,
            "Shizuku" to privilege.shizuku.label,
        )
        Metric.MONITOR -> buildList {
            if (monitor == null) {
                add("State" to "Background monitoring is off")
            } else {
                add("Pace" to monitor.level.label)
                add("Interval" to formatDuration(monitor.level.intervalMs))
                add("Android status" to ThermalStatus.label(monitor.thermalStatus))
                add("Sensors" to if (monitor.level.detail == DetailLevel.FULL) "Detailed (ambient + location)" else "Basic")
                add("Last sample" to formatTime(monitor.lastSampleAt))
                add("Next sample" to formatTime(monitor.nextSampleAt))
                add("Phone temperature" to "${formatTemp(monitor.referenceTemp)} · ${monitor.referenceSource.label}")
            }
            HeatLevel.cadence.forEach { level ->
                add(level.label to "Every ${formatDuration(level.intervalMs)} · ${level.statuses}")
            }
        }
    }
}

private fun batterySpecRows(s: ThermalSnapshot): List<Pair<String, String>> {
    val specs = s.specs
    return listOfNotNull(
        specs.batteryChemistry?.let { "Chemistry" to it },
        specs.designCapacityUAh?.let { "Design capacity" to formatMah(it) },
        specs.fullCapacityUAh?.let { "Full charge capacity" to formatMah(it) },
    )
}

private fun cpuSpecRows(s: ThermalSnapshot): List<Pair<String, String>> {
    val clusters = s.specs.cpuClusters
    if (clusters.isEmpty()) return emptyList()
    val text = clusters.joinToString(" · ") { "${it.cores}× ${(it.maxKHz / 1_000_000f).fmt(2)} GHz" }
    return listOf("Max frequency" to text)
}

private fun gpuSpecRows(s: ThermalSnapshot): List<Pair<String, String>> {
    val min = s.specs.gpuMinHz
    val max = s.specs.gpuMaxHz ?: return emptyList()
    val text = if (min != null && min != max) "${mhz(min)}–${mhz(max)} MHz" else "${mhz(max)} MHz"
    return listOf("Frequency range" to text)
}

private fun limitRows(thresholds: List<ZoneThreshold>, category: ZoneCategory): List<Pair<String, String>> =
    thresholds.filter { it.category == category }.flatMap { it.limitLines() }

private fun chargingLimitRows(thresholds: List<ZoneThreshold>): List<Pair<String, String>> =
    thresholds.filter { "charg" in it.name.lowercase() }.flatMap { it.limitLines() }

private fun ZoneThreshold.limitLines(): List<Pair<String, String>> = listOfNotNull(
    hot.takeIf { it.isNotEmpty() }?.let { name to it.joinToString(" · ") { step -> "${ThermalStatus.label(step.status)} ${formatTemp(step.celsius)}" } },
    cold.takeIf { it.isNotEmpty() }?.let { "$name cold" to it.joinToString(" · ") { step -> "${ThermalStatus.label(step.status)} ${formatTemp(step.celsius)}" } },
)

private fun headroomLimitRows(thresholds: List<Float?>): List<Pair<String, String>> =
    thresholds.mapIndexedNotNull { status, value ->
        value?.let { ThermalStatus.label(status) to "Headroom ${it.fmt(2)}" }
    }

private fun formatMah(microAh: Int): String = "${microAh / 1000} mAh"

private fun formatDbm(dbm: Int?): String = dbm?.let { "$it dBm" } ?: "—"

private fun mhz(hz: Long): Int = (hz / 1_000_000L).toInt()
