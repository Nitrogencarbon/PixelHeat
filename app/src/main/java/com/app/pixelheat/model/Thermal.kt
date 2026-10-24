package com.app.pixelheat.model

import kotlin.math.max

enum class ZoneCategory(val label: String) {
    CPU("CPU"),
    GPU("GPU"),
    BATTERY("Battery"),
    SKIN("Skin"),
    USB("USB port"),
    MODEM("Modem/Radio"),
    NPU("TPU/NPU"),
    DISPLAY("Display"),
    SOC("SoC"),
    OTHER("Other");

    companion object {
        /** Maps android.os.Temperature types; returns null for BCL types, which are not temperatures. */
        fun fromHalType(type: Int): ZoneCategory? = when (type) {
            0 -> CPU
            1 -> GPU
            2 -> BATTERY
            3 -> SKIN
            4 -> USB
            5 -> MODEM
            6, 7, 8 -> null
            9, 10 -> NPU
            11 -> DISPLAY
            12, 14 -> MODEM
            13 -> SOC
            else -> OTHER
        }

        fun fromName(name: String): ZoneCategory {
            val n = name.lowercase()
            return when {
                "batt" in n -> BATTERY
                "skin" in n || "neutral" in n || "quiet" in n -> SKIN
                "gpu" in n || "g3d" in n || "mali" in n -> GPU
                "tpu" in n || "npu" in n -> NPU
                "cpu" in n || n.startsWith("big") || n.startsWith("mid") ||
                    n.startsWith("little") || "cluster" in n -> CPU
                "modem" in n || "mdm" in n || "pa_therm" in n || n.startsWith("rf") || "wifi" in n -> MODEM
                "usb" in n -> USB
                "disp" in n -> DISPLAY
                "soc" in n -> SOC
                else -> OTHER
            }
        }
    }
}

data class ZoneTemp(
    val name: String,
    val category: ZoneCategory,
    val celsius: Float,
    val halStatus: Int? = null,
)

enum class ZoneSource(val label: String) {
    HAL_PRIVILEGED("Thermal HAL (privileged shell)"),
    SYSFS("sysfs thermal_zone"),
    PUBLIC_API("Public Android APIs"),
}

enum class PlugType(val label: String) {
    NONE("Unplugged"),
    AC("Charger"),
    USB("USB"),
    WIRELESS("Wireless"),
    DOCK("Dock"),
}

data class BatteryInfo(
    val tempC: Float,
    val levelPct: Int,
    val voltageMv: Int,
    /** Positive = current flowing into the battery. */
    val currentMa: Int,
    val isCharging: Boolean,
    val plugType: PlugType,
    val chargerMaxW: Float?,
    val health: Int,
    val cycleCount: Int?,
) {
    /** Positive while charging, negative while discharging. */
    val powerW: Float get() = voltageMv * currentMa / 1_000_000f

    val fastCharging: Boolean
        get() = isCharging && ((chargerMaxW ?: 0f) >= FAST_CHARGER_W || powerW >= FAST_MEASURED_W)

    companion object {
        const val FAST_CHARGER_W = 15f
        const val FAST_MEASURED_W = 10f
    }
}

enum class NetworkType(val label: String) {
    NONE("Offline"),
    WIFI("Wi-Fi"),
    CELLULAR("Mobile data"),
    ETHERNET("Ethernet"),
    OTHER("Other"),
}

/** Rates in KB/s. Signal levels are dBm; more negative means a weaker link. */
data class NetworkInfo(
    val type: NetworkType,
    val rxKBps: Float,
    val txKBps: Float,
    val wifiDbm: Int? = null,
    val cellDbm: Int? = null,
    /** Mobile download plus upload, KB/s. */
    val cellKBps: Float = 0f,
    /** Everything that is not mobile, usually Wi-Fi, KB/s. */
    val wifiKBps: Float = 0f,
) {
    val totalKBps: Float get() = rxKBps + txKBps
}

data class EnvironmentReading(
    val ambientSensorC: Float?,
    val humidityPct: Float?,
    val lux: Float?,
    /** Distance from the proximity sensor, centimetres. Near zero usually means a pocket or a case. */
    val proximityCm: Float? = null,
)

data class WeatherSnapshot(
    val tempC: Float,
    val shortwaveWm2: Float? = null,
    val windMs: Float? = null,
    val isDay: Boolean? = null,
    val at: Long = 0L,
)

enum class AmbientSource(val label: String) {
    SENSOR("Ambient sensor"),
    WEATHER("Local weather"),
    IDLE_BASELINE("Idle equilibrium"),
    THERMAL_MODEL("Thermal model"),
    FUSED("Fused estimate"),
    NONE("N/A"),
}

data class AmbientContribution(val label: String, val celsius: Float, val weight: Float)

data class AmbientEstimate(
    val celsius: Float?,
    val source: AmbientSource,
    val sigma: Float? = null,
    val outdoorLikelihood: Float? = null,
    val pocket: Boolean = false,
    val contributions: List<AmbientContribution> = emptyList(),
    /** Fused value before the saved correction. */
    val rawCelsius: Float? = null,
    /** Added to [rawCelsius]. Negative means the phone was reading warm. */
    val biasC: Float = 0f,
)

enum class DetailLevel { BASIC, FULL }

data class ThermalSnapshot(
    val timestamp: Long,
    val battery: BatteryInfo,
    val zones: List<ZoneTemp>,
    val zoneSource: ZoneSource,
    val thermalStatus: Int,
    val headroom: Float?,
    val cpuFreqPct: Float?,
    val screenOn: Boolean,
    val brightnessPct: Int?,
    val network: NetworkInfo,
    val gpsActive: Boolean,
    val cameraActive: Boolean,
    val audioActive: Boolean,
    val environment: EnvironmentReading?,
    val ambient: AmbientEstimate,
    val detail: DetailLevel,
    val thresholds: List<ZoneThreshold> = emptyList(),
    val specs: DeviceSpecs = DeviceSpecs.EMPTY,
    /** Headroom value at which each thermal status begins, index = [ThermalStatus]. */
    val headroomThresholds: List<Float?> = emptyList(),
) {
    fun hottest(category: ZoneCategory): Float? =
        zones.filter { it.category == category }.maxOfOrNull { it.celsius }

    val cpuTemp: Float? get() = hottest(ZoneCategory.CPU)
    val gpuTemp: Float? get() = hottest(ZoneCategory.GPU)
    val skinTemp: Float? get() = hottest(ZoneCategory.SKIN)
    val modemTemp: Float? get() = hottest(ZoneCategory.MODEM)
    val usbTemp: Float? get() = hottest(ZoneCategory.USB)
    val hottestZone: ZoneTemp? get() = zones.maxByOrNull { it.celsius }

    val maxTemp: Float get() = max(battery.tempC, hottestZone?.celsius ?: battery.tempC)

    /** Skin when the chassis sensor can be read, otherwise the battery. */
    val referenceSource: PhoneTempSource get() = phoneTempSource(skinTemp)

    /** Phone score shared across permission levels. */
    val referenceTemp: Float get() = phoneTemperature(skinTemp, battery.tempC)
}

/** Which sensor is supplying [ThermalSnapshot.referenceTemp]. */
enum class PhoneTempSource(val label: String) {
    SKIN("Skin"),
    BATTERY("Battery"),
}

/** Skin when it is a real reading, otherwise battery. */
fun phoneTemperature(skin: Float?, battery: Float): Float = skin?.takeIf { it.isFinite() } ?: battery

fun phoneTempSource(skin: Float?): PhoneTempSource =
    if (skin != null && skin.isFinite()) PhoneTempSource.SKIN else PhoneTempSource.BATTERY

/** One step of a HAL throttling ladder. [status] is a [ThermalStatus] level. */
data class ThrottleStep(val status: Int, val celsius: Float)

/** Hot and cold throttling limits published by the thermal HAL for one sensor. */
data class ZoneThreshold(
    val name: String,
    val category: ZoneCategory?,
    val hot: List<ThrottleStep>,
    val cold: List<ThrottleStep>,
)

data class CpuCluster(val cores: Int, val maxKHz: Long)

/** Hardware facts that do not change while the app is running. Missing fields were not exposed. */
data class DeviceSpecs(
    val batteryChemistry: String? = null,
    val designCapacityUAh: Int? = null,
    val fullCapacityUAh: Int? = null,
    val cpuClusters: List<CpuCluster> = emptyList(),
    val gpuMinHz: Long? = null,
    val gpuMaxHz: Long? = null,
) {
    companion object {
        val EMPTY = DeviceSpecs()
    }
}

object ThermalStatus {
    const val NONE = 0
    const val LIGHT = 1
    const val MODERATE = 2
    const val SEVERE = 3
    const val CRITICAL = 4
    const val EMERGENCY = 5
    const val SHUTDOWN = 6

    fun label(status: Int): String = when (status) {
        NONE -> "None"
        LIGHT -> "Light"
        MODERATE -> "Moderate"
        SEVERE -> "Severe"
        CRITICAL -> "Critical"
        EMERGENCY -> "Emergency"
        SHUTDOWN -> "Shutdown"
        else -> "Unknown"
    }
}

enum class HeatLevel(val label: String, val intervalMs: Long, val detail: DetailLevel, val statuses: String) {
    IDLE("Relaxed", 2 * 60_000L, DetailLevel.BASIC, "None"),
    COLD("Steady", 60_000L, DetailLevel.BASIC, "Light"),
    WARM("Frequent", 20_000L, DetailLevel.BASIC, "Moderate"),
    HOT("Rapid", 5_000L, DetailLevel.FULL, "Severe and above"),
    /** Kept so samples recorded with the old top level still load. Same pace as [HOT]. */
    CRITICAL("Rapid", 5_000L, DetailLevel.FULL, "Severe and above");

    companion object {
        val cadence = listOf(IDLE, COLD, WARM, HOT)
    }
}

enum class HardwareEventType(val label: String, val glyph: String) {
    SCREEN("Screen", "💡"),
    BRIGHTNESS("High brightness", "🔆"),
    GPS("GPS", "🛰"),
    CAMERA("Camera", "📷"),
    NETWORK("Network change", "📶"),
    DATA_BURST("Heavy data traffic", "⇅"),
    CHARGING("Charging", "🔌"),
    FAST_CHARGING("Fast charging", "⚡"),
    THERMAL("Thermal throttling", "🔥"),
    SUNLIGHT("Sunlight", "☀"),
}
