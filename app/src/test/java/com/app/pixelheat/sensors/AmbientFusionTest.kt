package com.app.pixelheat.sensors

import com.app.pixelheat.model.BatteryInfo
import com.app.pixelheat.model.EnvironmentReading
import com.app.pixelheat.model.PlugType
import com.app.pixelheat.model.WeatherSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AmbientFusionTest {

    @Test
    fun brightDaylightMeansOutdoors() {
        assertTrue(AmbientFusion.outdoorLikelihood(40_000f, isDay = true, gpsActive = false) > 0.85f)
    }

    @Test
    fun dimIndoorLightMeansIndoors() {
        assertTrue(AmbientFusion.outdoorLikelihood(80f, isDay = true, gpsActive = false) < 0.2f)
    }

    @Test
    fun indoorPriorIsAComfortableRoom() {
        assertEquals(24.0f, AmbientFusion.indoorPrior(20f), 0.2f)
        assertTrue(AmbientFusion.indoorPrior(35f) <= 28f)
        assertTrue(AmbientFusion.indoorPrior(-5f) >= 18f)
    }

    @Test
    fun indoorWeatherDoesNotDominateTheEstimate() {
        val obs = AmbientFusion.observations(
            battery = battery(temp = 36f, powerW = -0.4f),
            idle = false,
            pocket = false,
            outdoor = 0.1f,
            env = EnvironmentReading(null, null, 60f),
            weather = WeatherSnapshot(tempC = 4f, isDay = true),
            skinC = 34f,
            cpuC = 38f,
            rth = 2.5f,
            selfHeat = 0.8f,
        )
        val weather = obs.first { it.label == "Local weather" }
        val indoor = obs.first { it.label == "Indoor prior" }
        assertTrue(weather.r > indoor.r)
        val fused = AmbientFusion.assimilate(24f, 16f, obs)
        assertTrue(fused.x > 15f)
        assertTrue(fused.x < 33f)
    }

    @Test
    fun outdoorWeatherWeighsMoreThanTheIndoorPrior() {
        val obs = AmbientFusion.observations(
            battery = battery(temp = 28f, powerW = -0.3f),
            idle = false,
            pocket = false,
            outdoor = 0.9f,
            env = EnvironmentReading(null, null, 25_000f),
            weather = WeatherSnapshot(tempC = 8f, shortwaveWm2 = 400f, isDay = true),
            skinC = 27f,
            cpuC = 30f,
            rth = 2.5f,
            selfHeat = 0.8f,
        )
        val weather = obs.first { it.label == "Local weather" }
        val indoor = obs.first { it.label == "Indoor prior" }
        assertTrue(weather.r < indoor.r)
    }

    @Test
    fun pocketDropsIdleAndThermalObservations() {
        val obs = AmbientFusion.observations(
            battery = battery(temp = 37f, powerW = -0.2f),
            idle = true,
            pocket = true,
            outdoor = 0.2f,
            env = EnvironmentReading(null, null, 0f, proximityCm = 0f),
            weather = WeatherSnapshot(tempC = 18f),
            skinC = 36f,
            cpuC = 40f,
            rth = 2.5f,
            selfHeat = 0.8f,
        )
        assertFalse(obs.any { it.label == "Idle equilibrium" || it.label == "Thermal model" || it.label == "Skin" })
        assertTrue(obs.any { it.label == "Local weather" })
    }

    @Test
    fun proximityAgainstThePhoneCountsAsAPocket() {
        assertTrue(EnvironmentReading(null, null, 0f, proximityCm = 0.2f).isPocket())
        assertFalse(EnvironmentReading(null, null, 400f, proximityCm = 5f).isPocket())
        assertFalse(EnvironmentReading(null, null, 0f).isPocket())
    }

    private fun battery(temp: Float, powerW: Float) = BatteryInfo(
        tempC = temp,
        levelPct = 50,
        voltageMv = 4_000,
        currentMa = (powerW * 1_000_000f / 4_000f).toInt(),
        isCharging = powerW > 0f,
        plugType = if (powerW > 0f) PlugType.USB else PlugType.NONE,
        chargerMaxW = null,
        health = 2,
        cycleCount = null,
    )
}
