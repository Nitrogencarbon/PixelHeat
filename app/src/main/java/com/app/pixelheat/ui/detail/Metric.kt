package com.app.pixelheat.ui.detail

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CellTower
import androidx.compose.material.icons.rounded.DeveloperBoard
import androidx.compose.material.icons.rounded.DeviceThermostat
import androidx.compose.material.icons.rounded.GpsFixed
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.ui.graphics.vector.ImageVector
import com.app.pixelheat.data.db.ThermalSampleEntity
import com.app.pixelheat.model.HardwareEventType
import com.app.pixelheat.model.HeatLevel
import com.app.pixelheat.model.ThermalStatus
import com.app.pixelheat.model.ZoneCategory
import com.app.pixelheat.ui.theme.AmbientHeat
import com.app.pixelheat.ui.theme.CpuHeat
import com.app.pixelheat.ui.theme.HeatScale
import com.app.pixelheat.ui.theme.heatScale
import com.app.pixelheat.util.fmt
import com.app.pixelheat.util.formatPower
import com.app.pixelheat.util.formatRate
import com.app.pixelheat.util.formatTemp
import kotlin.math.abs
import kotlin.math.roundToInt

enum class ValueKind(val stepped: Boolean = false) {
    TEMPERATURE,
    PERCENT,
    POWER,
        RATE,
        LUX,
        DBM,
        ON_OFF(stepped = true),
    THERMAL_STATUS(stepped = true),
    HEAT_LEVEL(stepped = true);

    fun format(v: Float): String = when (this) {
        TEMPERATURE -> formatTemp(v)
        PERCENT -> "${v.roundToInt()}%"
        POWER -> formatPower(v)
        RATE -> formatRate(v)
        LUX -> "${v.roundToInt()} lux"
        DBM -> "${v.roundToInt()} dBm"
        ON_OFF -> if (v >= 0.5f) "On" else "Off"
        THERMAL_STATUS -> ThermalStatus.label(v.roundToInt())
        HEAT_LEVEL -> HeatLevel.entries.getOrNull(v.roundToInt())?.label ?: v.fmt(0)
    }

    /** Compact axis label. */
    fun axis(v: Float): String = when (this) {
        TEMPERATURE -> "${v.roundToInt()}°"
        POWER -> "${v.fmt(if (abs(v) < 10) 1 else 0)} W"
        DBM -> "${v.roundToInt()}"
        else -> format(v)
    }
}

/** A live-sensor tile that can be opened into a detail page; [key] matches the dashboard tile key. */
enum class Metric(
    val key: String,
    val title: String,
    val icon: ImageVector,
    val kind: ValueKind,
    val series: (ThermalSampleEntity) -> Float?,
    val about: String,
    val zoneCategory: ZoneCategory? = null,
    val eventTypes: Set<HardwareEventType> = emptySet(),
    val allZones: Boolean = false,
) {
    BATTERY_TEMP(
        "battery_temp", "Battery temperature", Icons.Rounded.Thermostat, ValueKind.TEMPERATURE, { it.batteryTemp },
        "Measured by the battery's own sensor. Lithium-ion cells age fastest when kept above 40 °C, " +
            "and Android slows down charging when the battery gets hot.",
        ZoneCategory.BATTERY, setOf(HardwareEventType.CHARGING, HardwareEventType.FAST_CHARGING),
    ),
    SKIN(
        "skin", "Skin temperature", Icons.Rounded.Smartphone, ValueKind.TEMPERATURE, { it.skinTemp },
        "A virtual sensor estimating the temperature of the phone's outer shell, which is what you feel in your hand. " +
            "Android's thermal throttling is driven mainly by this value.",
        ZoneCategory.SKIN, setOf(HardwareEventType.THERMAL, HardwareEventType.SUNLIGHT),
    ),
    CPU(
        "cpu", "CPU", Icons.Rounded.Memory, ValueKind.TEMPERATURE, { it.cpuTemp },
        "The hottest CPU cluster. Spikes follow heavy apps, games and background work; sustained high values " +
            "make the system lower clock frequencies.",
        ZoneCategory.CPU, setOf(HardwareEventType.THERMAL),
    ),
    GPU(
        "gpu", "GPU", Icons.Rounded.DeveloperBoard, ValueKind.TEMPERATURE, { it.gpuTemp },
        "The graphics processor. Games, camera previews, video effects and high refresh rates load it the most.",
        ZoneCategory.GPU, setOf(HardwareEventType.THERMAL, HardwareEventType.CAMERA),
    ),
    MODEM(
        "modem", "Modem / radio", Icons.Rounded.CellTower, ValueKind.TEMPERATURE, { it.modemTemp },
        "The cellular modem and RF front end. Weak signal, 5G and large transfers make it work harder and heat up.",
        ZoneCategory.MODEM, setOf(HardwareEventType.NETWORK, HardwareEventType.DATA_BURST),
    ),
    USB(
        "usb", "USB port", Icons.Rounded.Usb, ValueKind.TEMPERATURE, { it.usbTemp },
        "A sensor next to the USB-C connector. A fast rise while charging can point to a worn cable or a dirty port.",
        ZoneCategory.USB, setOf(HardwareEventType.CHARGING, HardwareEventType.FAST_CHARGING),
    ),
    HOTTEST(
        "hottest", "Hottest zone", Icons.Rounded.LocalFireDepartment, ValueKind.TEMPERATURE, { it.maxTemp },
        "The highest reading among all thermal zones at each moment. Internal zones run much hotter than the shell.",
        eventTypes = setOf(HardwareEventType.THERMAL), allZones = true,
    ),
    AMBIENT(
        "ambient", "Outside temperature", Icons.Rounded.DeviceThermostat, ValueKind.TEMPERATURE, { it.ambientTemp },
        "Estimated air temperature around the phone, fused from idle equilibrium, the chassis thermal model, " +
            "skin sensors and local weather (weighted by how likely you are outdoors). Approximate over a period, " +
            "not a live outdoor thermometer. A hot environment limits how fast the phone can shed heat.",
        eventTypes = setOf(HardwareEventType.SUNLIGHT),
    ),
    THERMAL_STATUS(
        "thermal_status", "Android thermal status", Icons.Rounded.Speed, ValueKind.THERMAL_STATUS, { it.thermalStatus.toFloat() },
        "Android's own throttling level, from None to Shutdown. From Moderate upward the system limits performance " +
            "to cool down. Headroom forecasts how close the device is to throttling (1.0 = throttling).",
        eventTypes = setOf(HardwareEventType.THERMAL),
    ),
    CHARGE(
        "level", "Battery level", Icons.Rounded.BatteryFull, ValueKind.PERCENT, { it.batteryLevel.toFloat() },
        "State of charge. Charging generates heat in the battery and the charging circuitry, especially above 80% " +
            "and with fast or wireless chargers.",
        eventTypes = setOf(HardwareEventType.CHARGING, HardwareEventType.FAST_CHARGING),
    ),
    POWER(
        "power", "Battery power", Icons.Rounded.Bolt, ValueKind.POWER, { it.powerW },
        "Power flowing into (positive) or out of (negative) the battery. Nearly all the energy the phone uses " +
            "ends up as heat.",
        eventTypes = setOf(HardwareEventType.CHARGING, HardwareEventType.FAST_CHARGING),
    ),
    SCREEN(
        "screen", "Display", Icons.Rounded.PhoneAndroid, ValueKind.PERCENT, { if (it.screenOn) (it.brightnessPct ?: 0).toFloat() else 0f },
        "Screen brightness while on (0% when off). The display is one of the largest power consumers; high " +
            "brightness in sunlight adds a lot of heat.",
        eventTypes = setOf(HardwareEventType.SCREEN, HardwareEventType.BRIGHTNESS),
    ),
    NETWORK(
        "network", "Network traffic", Icons.Rounded.Wifi, ValueKind.RATE, { it.rxKBps + it.txKBps },
        "Combined download and upload rate. Sustained transfers keep the modem or Wi-Fi radio busy, and mobile " +
            "data heats up much more than Wi-Fi. Signal is in dBm, and closer to zero is stronger. The chart is inverted: higher means a weaker link and more radio power.",
        eventTypes = setOf(HardwareEventType.NETWORK, HardwareEventType.DATA_BURST),
    ),
    GPS(
        "gps", "GPS", Icons.Rounded.GpsFixed, ValueKind.ON_OFF, { if (it.gpsActive) 1f else 0f },
        "Whether any app keeps the satellite receiver running. Navigation also keeps the screen, CPU and data " +
            "connection busy.",
        eventTypes = setOf(HardwareEventType.GPS),
    ),
    CAMERA(
        "camera", "Camera", Icons.Rounded.PhotoCamera, ValueKind.ON_OFF, { if (it.cameraActive) 1f else 0f },
        "Whether a camera is open. The image sensor, ISP and video encoder are among the hottest workloads, " +
            "especially when recording 4K or 60 fps.",
        eventTypes = setOf(HardwareEventType.CAMERA),
    ),
    AUDIO(
        "audio", "Audio", Icons.Rounded.MusicNote, ValueKind.ON_OFF, { if (it.audioActive) 1f else 0f },
        "Whether audio is playing. Playback itself is light, but streaming apps also keep the network active.",
    ),
    LIGHT(
        "light", "Ambient light", Icons.Rounded.LightMode, ValueKind.LUX, { it.lux },
        "Light hitting the front sensor, read only in detailed mode. Above about 25,000 lux the phone is likely " +
            "in direct sunlight and absorbing heat.",
        eventTypes = setOf(HardwareEventType.SUNLIGHT),
    ),
    SOURCE(
        "source", "Thermal data source", Icons.Rounded.Sensors, ValueKind.TEMPERATURE, { it.maxTemp },
        "Where temperatures come from. With root or Shizuku Pixel Heat reads the full thermal HAL; otherwise it " +
            "falls back to sysfs or the limited public Android APIs.",
        allZones = true,
    ),
    MONITOR(
        "monitor", "Sampling", Icons.Rounded.Timer, ValueKind.HEAT_LEVEL,
        { it.heatLevel.ordinal.coerceAtMost(HeatLevel.HOT.ordinal).toFloat() },
        "How often the background monitor samples. The pace follows Android's thermal status: Relaxed, Steady, " +
            "Frequent, then Rapid. From Severe upward the interval stays at 5 seconds.",
        eventTypes = setOf(HardwareEventType.THERMAL),
    );

    companion object {
        fun fromKey(key: String): Metric = entries.firstOrNull { it.key == key } ?: BATTERY_TEMP
    }
}

/** The scale that colors this metric, or null when the value is not a temperature. */
fun Metric.heatScale(): HeatScale? = when (this) {
    Metric.AMBIENT -> AmbientHeat
    Metric.HOTTEST -> CpuHeat
    Metric.BATTERY_TEMP, Metric.SKIN, Metric.CPU, Metric.GPU, Metric.MODEM, Metric.USB -> zoneCategory?.heatScale()
    else -> null
}
