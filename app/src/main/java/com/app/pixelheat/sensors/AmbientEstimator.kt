package com.app.pixelheat.sensors

import com.app.pixelheat.data.prefs.AppPreferences
import com.app.pixelheat.model.AmbientContribution
import com.app.pixelheat.model.AmbientEstimate
import com.app.pixelheat.model.AmbientSource
import com.app.pixelheat.model.BatteryInfo
import com.app.pixelheat.model.EnvironmentReading
import com.app.pixelheat.model.WeatherSnapshot
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Fuses every cheap signal we already have into one ambient-air estimate. Pixels have no ambient
 * thermometer, so the filter combines idle equilibrium, a chassis thermal model, local weather
 * (weighted by how likely we are outdoors) and an indoor comfort prior. Extra hardware is only the
 * proximity sensor, read with the other environment sensors on FULL samples.
 */
class AmbientEstimator(private val prefs: AppPreferences) {
    private var refTemp = Float.NaN
    private var refAt = 0L
    private var idleSlope = Float.POSITIVE_INFINITY

    @Synchronized
    fun estimate(
        battery: BatteryInfo,
        screenOn: Boolean,
        env: EnvironmentReading?,
        weather: WeatherSnapshot?,
        skinC: Float?,
        cpuC: Float?,
        gpsActive: Boolean,
        now: Long,
    ): AmbientEstimate {
        if (battery.tempC.isNaN()) return AmbientEstimate(null, AmbientSource.NONE)
        val idle = updateIdle(battery, screenOn, now)
        val pocket = env.isPocket()
        val outdoor = AmbientFusion.outdoorLikelihood(env?.lux, weather?.isDay, gpsActive)
        val observations = AmbientFusion.observations(
            battery = battery,
            idle = idle,
            pocket = pocket,
            outdoor = outdoor,
            env = env,
            weather = weather,
            skinC = skinC,
            cpuC = cpuC,
            rth = prefs.fusionRth.coerceIn(RTH_MIN, RTH_MAX),
            selfHeat = prefs.fusionSelfHeat.coerceIn(SELF_MIN, SELF_MAX),
        )
        if (observations.isEmpty()) return AmbientEstimate(null, AmbientSource.NONE)

        var x = prefs.fusionX
        var p = prefs.fusionP
        if (x.isNaN() || p.isNaN() || p <= 0f) {
            x = observations.minBy { it.r }.z
            p = 16f
        } else {
            val hours = ((now - prefs.fusionAt).coerceAtLeast(0L) / 3_600_000f).coerceAtMost(24f)
            p += AmbientFusion.PROCESS_NOISE * hours
        }

        val updated = AmbientFusion.assimilate(x, p, observations)
        x = updated.x
        p = updated.p
        val chassis = skinC ?: battery.tempC
        if (AmbientFusion.heatW(battery) > 1f) x = min(x, chassis)

        persistCalibration(prefs, battery, weather, outdoor, idle, pocket, x, now)
        prefs.fusionX = x
        prefs.fusionP = p
        prefs.fusionAt = now

        val bias = prefs.ambientBiasC.coerceIn(BIAS_MIN, BIAS_MAX)
        return AmbientEstimate(
            celsius = x + bias,
            source = AmbientSource.FUSED,
            sigma = sqrt(p),
            outdoorLikelihood = outdoor,
            pocket = pocket,
            contributions = updated.contributions,
            rawCelsius = x,
            biasC = bias,
        )
    }

    private fun updateIdle(battery: BatteryInfo, screenOn: Boolean, now: Long): Boolean {
        if (refTemp.isNaN() || now - refAt > REF_RESET_MS) {
            refTemp = battery.tempC
            refAt = now
            idleSlope = Float.POSITIVE_INFINITY
            return false
        }
        val dtMin = (now - refAt) / 60_000f
        if (dtMin < MIN_EQUILIBRIUM_MIN) return false
        idleSlope = (battery.tempC - refTemp) / dtMin
        refTemp = battery.tempC
        refAt = now
        val idle = !screenOn && !battery.isCharging && abs(battery.powerW) < IDLE_POWER_W && abs(idleSlope) < EQUILIBRIUM_SLOPE
        if (idle) {
            prefs.idleBaselineTemp = battery.tempC - prefs.fusionSelfHeat
            prefs.idleBaselineAt = now
        }
        return idle
    }

    private fun persistCalibration(
        prefs: AppPreferences,
        battery: BatteryInfo,
        weather: WeatherSnapshot?,
        outdoor: Float,
        idle: Boolean,
        pocket: Boolean,
        x: Float,
        now: Long,
    ) {
        if (pocket) return
        if (idle) {
            val residual = battery.tempC - x
            prefs.fusionSelfHeat = (0.95f * prefs.fusionSelfHeat + 0.05f * residual).coerceIn(SELF_MIN, SELF_MAX)
        }
        val heat = AmbientFusion.heatW(battery)
        val outdoorWeather = weather?.tempC?.takeIf { outdoor >= 0.7f && now - weather.at < WEATHER_FRESH_MS }
        if (heat > 2f && outdoorWeather != null) {
            val measured = ((battery.tempC - outdoorWeather) / heat).coerceIn(RTH_MIN, RTH_MAX)
            prefs.fusionRth = (0.98f * prefs.fusionRth + 0.02f * measured).coerceIn(RTH_MIN, RTH_MAX)
        }
    }

    private companion object {
        const val REF_RESET_MS = 60 * 60_000L
        const val MIN_EQUILIBRIUM_MIN = 5f
        const val EQUILIBRIUM_SLOPE = 0.05f
        const val IDLE_POWER_W = 0.8f
        const val WEATHER_FRESH_MS = 90 * 60_000L
        const val RTH_MIN = 1.5f
        const val RTH_MAX = 4.0f
        const val SELF_MIN = 0.3f
        const val SELF_MAX = 2.5f
        const val BIAS_MIN = -20f
        const val BIAS_MAX = 20f
    }
}

internal data class AmbientObservation(val label: String, val z: Float, val r: Float)

internal data class FusionResult(
    val x: Float,
    val p: Float,
    val contributions: List<AmbientContribution>,
)

internal object AmbientFusion {
    const val PROCESS_NOISE = 0.4f

    fun outdoorLikelihood(lux: Float?, isDay: Boolean?, gpsActive: Boolean): Float {
        var p = when {
            lux == null -> 0.35f
            isDay == false -> when {
                lux >= 8_000f -> 0.7f
                lux >= 200f -> 0.3f
                else -> 0.2f
            }
            lux >= 15_000f -> 0.92f
            lux >= 8_000f -> 0.75f
            lux >= 2_000f -> 0.4f
            lux >= 200f -> 0.18f
            else -> 0.1f
        }
        if (gpsActive) p = min(0.98f, p + 0.08f)
        return p
    }

    /** ASHRAE 55 adaptive comfort: typical indoor air from the prevailing outdoor temperature. */
    fun indoorPrior(outdoorC: Float): Float = (0.31f * outdoorC + 17.8f).coerceIn(18f, 28f)

    fun heatW(battery: BatteryInfo): Float =
        if (battery.isCharging) max(battery.powerW, 0f) * 0.1f + 0.5f else abs(min(battery.powerW, 0f))

    fun observations(
        battery: BatteryInfo,
        idle: Boolean,
        pocket: Boolean,
        outdoor: Float,
        env: EnvironmentReading?,
        weather: WeatherSnapshot?,
        skinC: Float?,
        cpuC: Float?,
        rth: Float,
        selfHeat: Float,
    ): List<AmbientObservation> {
        val out = ArrayList<AmbientObservation>(5)
        env?.ambientSensorC?.let { out += AmbientObservation("Ambient sensor", it, 0.25f) }

        if (idle && !pocket) {
            out += AmbientObservation("Idle equilibrium", battery.tempC - selfHeat, 0.6f)
        }

        if (!pocket) {
            val solarW = outdoor * (weather?.shortwaveWm2 ?: 0f) / 1_000f * 2f
            val chassis = skinC ?: battery.tempC
            val z = chassis - rth * (heatW(battery) + solarW)
            val r = 1.2f + heatW(battery) + if (skinC == null) 0.8f else 0f
            out += AmbientObservation("Thermal model", z, r)
        }

        if (skinC != null && cpuC != null && !pocket) {
            val leftover = max(0f, cpuC - skinC)
            out += AmbientObservation("Skin", skinC - 0.25f * leftover, 2.2f + leftover / 8f)
        }

        weather?.tempC?.let { outdoorC ->
            val pOut = outdoor.coerceIn(0.05f, 0.95f)
            out += AmbientObservation("Local weather", outdoorC, 1.6f / pOut)
            out += AmbientObservation("Indoor prior", indoorPrior(outdoorC), 9f / (1f - pOut))
        }
        return out
    }

    fun assimilate(x0: Float, p0: Float, observations: List<AmbientObservation>): FusionResult {
        var x = x0
        var p = p0
        val gains = FloatArray(observations.size)
        observations.forEachIndexed { i, o ->
            val k = p / (p + o.r)
            x += k * (o.z - x)
            p *= 1f - k
            gains[i] = k
        }
        val gainSum = gains.sum().takeIf { it > 0f } ?: 1f
        val contributions = observations.mapIndexed { i, o ->
            AmbientContribution(o.label, o.z, gains[i] / gainSum)
        }
        return FusionResult(x, p.coerceAtLeast(0.04f), contributions)
    }
}

internal fun EnvironmentReading?.isPocket(): Boolean =
    this?.proximityCm?.let { it <= 1.5f } ?: false
