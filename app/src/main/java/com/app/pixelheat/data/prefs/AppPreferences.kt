package com.app.pixelheat.data.prefs

import android.content.Context
import androidx.core.content.edit
import com.app.pixelheat.model.Appearance
import com.app.pixelheat.model.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AppPreferences(context: Context) {
    private val sp = context.getSharedPreferences("pixel_heat", Context.MODE_PRIVATE)

    private val _monitoringEnabled = MutableStateFlow(sp.getBoolean(K_MONITORING, true))
    val monitoringEnabled: StateFlow<Boolean> = _monitoringEnabled.asStateFlow()

    fun setMonitoringEnabled(enabled: Boolean) {
        sp.edit { putBoolean(K_MONITORING, enabled) }
        _monitoringEnabled.value = enabled
    }

    /** Daily copy of the measurements into Downloads. Off until the user turns it on. */
    private val _autoExport = MutableStateFlow(sp.getBoolean(K_AUTO_EXPORT, false))
    val autoExport: StateFlow<Boolean> = _autoExport.asStateFlow()

    fun setAutoExport(enabled: Boolean) {
        sp.edit { putBoolean(K_AUTO_EXPORT, enabled) }
        _autoExport.value = enabled
    }

    private val _lastExportAt = MutableStateFlow(sp.getLong(K_LAST_EXPORT, 0L))
    val lastExportAt: StateFlow<Long> = _lastExportAt.asStateFlow()

    fun markExported(at: Long) {
        sp.edit { putLong(K_LAST_EXPORT, at) }
        _lastExportAt.value = at
    }

    fun collectedEstimate() = CollectedEstimate(
        idleBaselineTemp = idleBaselineTemp,
        idleBaselineAt = idleBaselineAt,
        fusionX = fusionX,
        fusionP = fusionP,
        fusionAt = fusionAt,
        fusionRth = fusionRth,
        fusionSelfHeat = fusionSelfHeat,
        ambientBiasC = ambientBiasC,
        weatherTemp = weatherTemp,
        weatherAt = weatherAt,
        // Backups land in public Downloads: the location the weather was fetched for stays on the phone.
        weatherLat = Float.NaN,
        weatherLon = Float.NaN,
        weatherShortwave = weatherShortwave,
        weatherWindMs = weatherWindMs,
        weatherIsDay = weatherIsDay,
    )

    /**
     * Held by the ambient estimator for a whole read-update-write cycle and by restore and clear, so a
     * restored or cleared estimate is not overwritten by a sample that read the old values.
     */
    val estimateLock = Any()

    fun restoreCollectedEstimate(state: CollectedEstimate) = synchronized(estimateLock) {
        idleBaselineTemp = state.idleBaselineTemp
        idleBaselineAt = state.idleBaselineAt
        fusionX = state.fusionX
        fusionP = state.fusionP
        fusionAt = state.fusionAt
        fusionRth = state.fusionRth
        fusionSelfHeat = state.fusionSelfHeat
        ambientBiasC = state.ambientBiasC
        saveWeather(
            tempC = state.weatherTemp,
            lat = state.weatherLat.toDouble(),
            lon = state.weatherLon.toDouble(),
            at = state.weatherAt,
            shortwaveWm2 = state.weatherShortwave,
            windMs = state.weatherWindMs,
            isDay = state.weatherIsDay,
        )
    }

    /** Drops measurements kept outside the database. Settings, consent and permissions stay. */
    fun clearCollectedEstimate() = synchronized(estimateLock) {
        sp.edit {
            listOf(
                K_BASELINE_T, K_BASELINE_AT,
                K_FUSION_X, K_FUSION_P, K_FUSION_AT, K_FUSION_RTH, K_FUSION_SELF,
                K_WEATHER_T, K_WEATHER_AT, K_WEATHER_LAT, K_WEATHER_LON, K_WEATHER_SW, K_WEATHER_WIND, K_WEATHER_DAY,
            ).forEach(::remove)
            putFloat(K_AMBIENT_BIAS, -5f)
        }
    }

    /** Whether the user allowed sending their approximate location to Open-Meteo. Off until granted. */
    private val _weatherConsent = MutableStateFlow(sp.getBoolean(K_WEATHER_CONSENT, false))
    val weatherConsent: StateFlow<Boolean> = _weatherConsent.asStateFlow()

    /** Revoking also drops the stored weather and the coordinates it was fetched for. */
    @Synchronized
    fun setWeatherConsent(granted: Boolean) {
        sp.edit {
            putBoolean(K_WEATHER_CONSENT, granted)
            if (!granted) {
                listOf(K_WEATHER_T, K_WEATHER_AT, K_WEATHER_LAT, K_WEATHER_LON, K_WEATHER_SW, K_WEATHER_WIND, K_WEATHER_DAY)
                    .forEach(::remove)
            }
        }
        _weatherConsent.value = granted
    }

    private val _appearance = MutableStateFlow(readAppearance())
    val appearance: StateFlow<Appearance> = _appearance.asStateFlow()

    fun updateAppearance(transform: (Appearance) -> Appearance) {
        val next = transform(_appearance.value)
        sp.edit {
            putString(K_THEME_MODE, next.themeMode.name)
            putBoolean(K_PURE_BLACK, next.pureBlack)
        }
        _appearance.value = next
    }

    private fun readAppearance() = Appearance(
        themeMode = sp.getString(K_THEME_MODE, null)
            ?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } } ?: ThemeMode.SYSTEM,
        pureBlack = sp.getBoolean(K_PURE_BLACK, false),
    )

    var rootGranted: Boolean
        get() = sp.getBoolean(K_ROOT, false)
        set(value) = sp.edit { putBoolean(K_ROOT, value) }

    /** False until the first-run permission cards have been completed. */
    var accessIntroDone: Boolean
        get() = sp.getBoolean(K_ACCESS_INTRO, false)
        set(value) = sp.edit { putBoolean(K_ACCESS_INTRO, value) }

    var lastAlertAt: Long
        get() = sp.getLong(K_LAST_ALERT, 0L)
        set(value) = sp.edit { putLong(K_LAST_ALERT, value) }

    var idleBaselineTemp: Float
        get() = sp.getFloat(K_BASELINE_T, Float.NaN)
        set(value) = sp.edit { putFloat(K_BASELINE_T, value) }

    var idleBaselineAt: Long
        get() = sp.getLong(K_BASELINE_AT, 0L)
        set(value) = sp.edit { putLong(K_BASELINE_AT, value) }

    /**
     * Ignored without Open-Meteo consent. That covers a fetch still in flight when the user revokes
     * it and a backup restored while it is off. Locked with [setWeatherConsent] so the two cannot interleave.
     */
    @Synchronized
    fun saveWeather(
        tempC: Float,
        lat: Double,
        lon: Double,
        at: Long,
        shortwaveWm2: Float = Float.NaN,
        windMs: Float = Float.NaN,
        isDay: Boolean? = null,
    ) {
        if (!_weatherConsent.value) return
        sp.edit {
        putFloat(K_WEATHER_T, tempC)
        putFloat(K_WEATHER_LAT, lat.toFloat())
        putFloat(K_WEATHER_LON, lon.toFloat())
        putLong(K_WEATHER_AT, at)
        putFloat(K_WEATHER_SW, shortwaveWm2)
        putFloat(K_WEATHER_WIND, windMs)
        if (isDay != null) putBoolean(K_WEATHER_DAY, isDay) else remove(K_WEATHER_DAY)
        }
    }

    val weatherTemp: Float get() = sp.getFloat(K_WEATHER_T, Float.NaN)
    val weatherAt: Long get() = sp.getLong(K_WEATHER_AT, 0L)
    val weatherLat: Float get() = sp.getFloat(K_WEATHER_LAT, Float.NaN)
    val weatherLon: Float get() = sp.getFloat(K_WEATHER_LON, Float.NaN)
    val weatherShortwave: Float get() = sp.getFloat(K_WEATHER_SW, Float.NaN)
    val weatherWindMs: Float get() = sp.getFloat(K_WEATHER_WIND, Float.NaN)
    val weatherIsDay: Boolean? get() = if (sp.contains(K_WEATHER_DAY)) sp.getBoolean(K_WEATHER_DAY, true) else null

    var fusionX: Float
        get() = sp.getFloat(K_FUSION_X, Float.NaN)
        set(value) = sp.edit { putFloat(K_FUSION_X, value) }
    var fusionP: Float
        get() = sp.getFloat(K_FUSION_P, Float.NaN)
        set(value) = sp.edit { putFloat(K_FUSION_P, value) }
    var fusionAt: Long
        get() = sp.getLong(K_FUSION_AT, 0L)
        set(value) = sp.edit { putLong(K_FUSION_AT, value) }
    var fusionRth: Float
        get() = sp.getFloat(K_FUSION_RTH, 2.5f)
        set(value) = sp.edit { putFloat(K_FUSION_RTH, value) }
    var fusionSelfHeat: Float
        get() = sp.getFloat(K_FUSION_SELF, 0.8f)
        set(value) = sp.edit { putFloat(K_FUSION_SELF, value) }

    /** Thermal-history series names the user chose to draw. Null until they change the default. */
    fun chartSeriesNames(): Set<String>? = sp.getStringSet(K_CHART_SERIES, null)?.toSet()

    fun setChartSeriesNames(names: Set<String>) {
        sp.edit { putStringSet(K_CHART_SERIES, names.toSet()) }
    }

    /** Degrees added to the fused ambient estimate. Starts at −5 °C from a real outdoor comparison. */
    var ambientBiasC: Float
        get() = sp.getFloat(K_AMBIENT_BIAS, -5f)
        set(value) = sp.edit { putFloat(K_AMBIENT_BIAS, value) }

    private companion object {
        const val K_MONITORING = "monitoring_enabled"
        const val K_ROOT = "root_granted"
        const val K_ACCESS_INTRO = "setup_cards_done"
        const val K_LAST_ALERT = "last_alert_at"
        const val K_BASELINE_T = "idle_baseline_temp"
        const val K_BASELINE_AT = "idle_baseline_at"
        const val K_WEATHER_T = "weather_temp"
        const val K_WEATHER_AT = "weather_at"
        const val K_WEATHER_LAT = "weather_lat"
        const val K_WEATHER_LON = "weather_lon"
        const val K_WEATHER_SW = "weather_shortwave"
        const val K_WEATHER_WIND = "weather_wind"
        const val K_WEATHER_DAY = "weather_is_day"
        const val K_WEATHER_CONSENT = "weather_consent"
        const val K_FUSION_X = "fusion_x"
        const val K_FUSION_P = "fusion_p"
        const val K_FUSION_AT = "fusion_at"
        const val K_FUSION_RTH = "fusion_rth"
        const val K_FUSION_SELF = "fusion_self"
        const val K_AMBIENT_BIAS = "ambient_bias_c"
        const val K_THEME_MODE = "theme_mode"
        const val K_PURE_BLACK = "pure_black"
        const val K_CHART_SERIES = "chart_series"
        const val K_AUTO_EXPORT = "auto_export"
        const val K_LAST_EXPORT = "last_export_at"
    }
}

/** Readings kept in preferences rather than the database: the outside-temperature estimate and its weather cache. */
data class CollectedEstimate(
    val idleBaselineTemp: Float,
    val idleBaselineAt: Long,
    val fusionX: Float,
    val fusionP: Float,
    val fusionAt: Long,
    val fusionRth: Float,
    val fusionSelfHeat: Float,
    val ambientBiasC: Float,
    val weatherTemp: Float,
    val weatherAt: Long,
    val weatherLat: Float,
    val weatherLon: Float,
    val weatherShortwave: Float,
    val weatherWindMs: Float,
    val weatherIsDay: Boolean?,
)
