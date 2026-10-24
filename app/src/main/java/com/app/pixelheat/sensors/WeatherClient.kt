package com.app.pixelheat.sensors

import com.app.pixelheat.data.prefs.AppPreferences
import com.app.pixelheat.model.WeatherSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.abs

/** Outdoor weather from Open-Meteo (free, no API key). Coordinates are rounded to ~1 km. */
class WeatherClient(private val prefs: AppPreferences) {

    @Volatile
    private var lastAttemptAt = 0L

    fun cached(maxAgeMs: Long = CACHE_MAX_AGE_MS): WeatherSnapshot? {
        val t = prefs.weatherTemp
        if (t.isNaN() || System.currentTimeMillis() - prefs.weatherAt > maxAgeMs) return null
        return WeatherSnapshot(
            tempC = t,
            shortwaveWm2 = prefs.weatherShortwave.takeIf { !it.isNaN() },
            windMs = prefs.weatherWindMs.takeIf { !it.isNaN() },
            isDay = prefs.weatherIsDay,
            at = prefs.weatherAt,
        )
    }

    suspend fun current(lat: Double, lon: Double): WeatherSnapshot? {
        val now = System.currentTimeMillis()
        val sameArea = abs(prefs.weatherLat - lat) < 0.1 && abs(prefs.weatherLon - lon) < 0.1
        if (sameArea && now - prefs.weatherAt < REFRESH_MS) return cached()
        if (now - lastAttemptAt < RETRY_MS) return cached()
        lastAttemptAt = now
        return withContext(Dispatchers.IO) {
            runCatching { fetch(lat, lon) }.getOrNull()
                ?.also {
                    prefs.saveWeather(
                        tempC = it.tempC,
                        lat = lat,
                        lon = lon,
                        at = now,
                        shortwaveWm2 = it.shortwaveWm2 ?: Float.NaN,
                        windMs = it.windMs ?: Float.NaN,
                        isDay = it.isDay,
                    )
                }
                ?: cached()
        }
    }

    private fun fetch(lat: Double, lon: Double): WeatherSnapshot {
        val url = URL(
            String.format(
                Locale.US,
                "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f" +
                    "&current=temperature_2m,shortwave_radiation,wind_speed_10m,is_day&wind_speed_unit=ms",
                lat, lon,
            ),
        )
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        try {
            check(conn.responseCode == HttpURLConnection.HTTP_OK)
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val current = JSONObject(body).getJSONObject("current")
            fun optionalFloat(key: String): Float? =
                if (current.has(key) && !current.isNull(key)) current.getDouble(key).toFloat() else null
            return WeatherSnapshot(
                tempC = current.getDouble("temperature_2m").toFloat(),
                shortwaveWm2 = optionalFloat("shortwave_radiation"),
                windMs = optionalFloat("wind_speed_10m"),
                isDay = if (current.has("is_day") && !current.isNull("is_day")) current.getInt("is_day") == 1 else null,
                at = System.currentTimeMillis(),
            )
        } finally {
            conn.disconnect()
        }
    }

    private companion object {
        const val REFRESH_MS = 30 * 60_000L
        const val RETRY_MS = 10 * 60_000L
        const val CACHE_MAX_AGE_MS = 90 * 60_000L
        const val TIMEOUT_MS = 5_000
    }
}
