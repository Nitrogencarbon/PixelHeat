package com.app.pixelheat.ui.theme

import androidx.compose.ui.graphics.Color
import com.app.pixelheat.model.HardwareEventType
import com.app.pixelheat.model.ThermalStatus
import com.app.pixelheat.model.ZoneCategory

/** Seed of the app's only color scheme. */
val BrandGreen = Color(0xFF7CB342)

// Data semantics: these stay fixed regardless of the color scheme so that cold → hot reads the same everywhere.
val Ember = Color(0xFFFF6E40)
val Frost = Color(0xFF4FC3F7)
val Amber = Color(0xFFFFC857)

val SeriesBattery = Color(0xFFFFB74D)
val SeriesCpu = Color(0xFFFF5252)
val SeriesGpu = Color(0xFFBA68C8)
val SeriesSkin = Color(0xFF4DD0E1)
val AmbientBar = Color(0xFF64B5F6)

val Good = Color(0xFF66BB6A)
val Neutral = Color(0xFF8A8F98)
private val Hot = Color(0xFFE53935)

/**
 * Maps one sensor's readings onto the shared cold → hot colors and onto a 0–1 bar.
 * [coolUntil] is still cold, [normalUntil] is everyday operation, [warmUntil] is worth watching,
 * [hotUntil] is where this sensor starts limiting itself.
 */
data class HeatScale(
    val emptyAt: Float,
    val fullAt: Float,
    val coolUntil: Float,
    val normalUntil: Float,
    val warmUntil: Float,
    val hotUntil: Float,
) {
    /** 0 cold, 1 normal, 2 warm, 3 hot, 4 critical. */
    fun band(c: Float): Int = when {
        c < coolUntil -> 0
        c < normalUntil -> 1
        c < warmUntil -> 2
        c < hotUntil -> 3
        else -> 4
    }

    fun fraction(c: Float?): Float? =
        c?.takeIf { !it.isNaN() }?.let { ((it - emptyAt) / (fullAt - emptyAt)).coerceIn(0f, 1f) }

    fun color(c: Float?): Color = when {
        c == null || c.isNaN() -> Neutral
        else -> when (band(c)) {
            0 -> Frost
            1 -> Good
            2 -> Amber
            3 -> Ember
            else -> Hot
        }
    }
}

val BatteryHeat = HeatScale(emptyAt = 20f, fullAt = 50f, coolUntil = 28f, normalUntil = 36f, warmUntil = 41f, hotUntil = 46f)
val SkinHeat = HeatScale(emptyAt = 22f, fullAt = 52f, coolUntil = 30f, normalUntil = 37f, warmUntil = 42f, hotUntil = 47f)
val CpuHeat = HeatScale(emptyAt = 30f, fullAt = 105f, coolUntil = 42f, normalUntil = 62f, warmUntil = 78f, hotUntil = 95f)
val GpuHeat = HeatScale(emptyAt = 30f, fullAt = 100f, coolUntil = 40f, normalUntil = 58f, warmUntil = 74f, hotUntil = 90f)
val ModemHeat = HeatScale(emptyAt = 25f, fullAt = 90f, coolUntil = 35f, normalUntil = 52f, warmUntil = 65f, hotUntil = 78f)
val UsbHeat = HeatScale(emptyAt = 20f, fullAt = 80f, coolUntil = 32f, normalUntil = 46f, warmUntil = 58f, hotUntil = 70f)
val AmbientHeat = HeatScale(emptyAt = 0f, fullAt = 42f, coolUntil = 12f, normalUntil = 26f, warmUntil = 32f, hotUntil = 38f)

fun ZoneCategory.heatScale(): HeatScale = when (this) {
    ZoneCategory.CPU, ZoneCategory.SOC, ZoneCategory.NPU -> CpuHeat
    ZoneCategory.GPU -> GpuHeat
    ZoneCategory.BATTERY -> BatteryHeat
    ZoneCategory.SKIN, ZoneCategory.DISPLAY -> SkinHeat
    ZoneCategory.MODEM -> ModemHeat
    ZoneCategory.USB -> UsbHeat
    ZoneCategory.OTHER -> CpuHeat
}

/** Body-temperature scale, for the battery and for values that describe how the phone feels. */
fun temperatureColor(c: Float?): Color = BatteryHeat.color(c)

fun temperatureColor(c: Float?, scale: HeatScale): Color = scale.color(c)

fun thermalStatusColor(status: Int): Color = when (status) {
    ThermalStatus.NONE -> Frost
    ThermalStatus.LIGHT -> Good
    ThermalStatus.MODERATE -> Amber
    ThermalStatus.SEVERE -> Ember
    else -> Hot
}

/** None leaves the bar empty; Shutdown fills it. */
fun thermalStatusFraction(status: Int): Float =
    status.coerceIn(ThermalStatus.NONE, ThermalStatus.SHUTDOWN) / ThermalStatus.SHUTDOWN.toFloat()

fun HardwareEventType.color(): Color = when (this) {
    HardwareEventType.SCREEN -> Color(0xFFFFF176)
    HardwareEventType.BRIGHTNESS -> Color(0xFFFFD54F)
    HardwareEventType.GPS -> Color(0xFF81C784)
    HardwareEventType.CAMERA -> Color(0xFFF06292)
    HardwareEventType.NETWORK -> Color(0xFF4FC3F7)
    HardwareEventType.DATA_BURST -> Color(0xFF29B6F6)
    HardwareEventType.CHARGING -> Color(0xFFAED581)
    HardwareEventType.FAST_CHARGING -> Color(0xFFFFEE58)
    HardwareEventType.THERMAL -> Color(0xFFFF7043)
    HardwareEventType.SUNLIGHT -> Color(0xFFFFCA28)
}
