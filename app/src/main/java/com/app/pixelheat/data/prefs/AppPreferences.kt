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

    private val _appearance = MutableStateFlow(readAppearance())
    val appearance: StateFlow<Appearance> = _appearance.asStateFlow()

    fun updateAppearance(transform: (Appearance) -> Appearance) {
        val next = transform(_appearance.value)
        sp.edit {
            putString(K_THEME_MODE, next.themeMode.name)
            putBoolean(K_DYNAMIC_COLOR, next.dynamicColor)
            putInt(K_SEED, next.seedArgb)
            putBoolean(K_PURE_BLACK, next.pureBlack)
        }
        _appearance.value = next
    }

    private fun readAppearance() = Appearance(
        themeMode = sp.getString(K_THEME_MODE, null)
            ?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } } ?: ThemeMode.SYSTEM,
        dynamicColor = sp.getBoolean(K_DYNAMIC_COLOR, true),
        seedArgb = sp.getInt(K_SEED, Appearance.DEFAULT_SEED),
        pureBlack = sp.getBoolean(K_PURE_BLACK, false),
    )

    var rootGranted: Boolean
        get() = sp.getBoolean(K_ROOT, false)
        set(value) = sp.edit { putBoolean(K_ROOT, value) }

    var onboardingDone: Boolean
        get() = sp.getBoolean(K_ONBOARDING, false)
        set(value) = sp.edit { putBoolean(K_ONBOARDING, value) }

    var lastAlertAt: Long
        get() = sp.getLong(K_LAST_ALERT, 0L)
        set(value) = sp.edit { putLong(K_LAST_ALERT, value) }

    var idleBaselineTemp: Float
        get() = sp.getFloat(K_BASELINE_T, Float.NaN)
        set(value) = sp.edit { putFloat(K_BASELINE_T, value) }

    var idleBaselineAt: Long
        get() = sp.getLong(K_BASELINE_AT, 0L)
        set(value) = sp.edit { putLong(K_BASELINE_AT, value) }

    fun saveWeather(
        tempC: Float,
        lat: Double,
        lon: Double,
        at: Long,
        shortwaveWm2: Float = Float.NaN,
        windMs: Float = Float.NaN,
        isDay: Boolean? = null,
    ) = sp.edit {
        putFloat(K_WEATHER_T, tempC)
        putFloat(K_WEATHER_LAT, lat.toFloat())
        putFloat(K_WEATHER_LON, lon.toFloat())
        putLong(K_WEATHER_AT, at)
        putFloat(K_WEATHER_SW, shortwaveWm2)
        putFloat(K_WEATHER_WIND, windMs)
        if (isDay != null) putBoolean(K_WEATHER_DAY, isDay) else remove(K_WEATHER_DAY)
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
        const val K_ONBOARDING = "onboarding_done"
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
        const val K_FUSION_X = "fusion_x"
        const val K_FUSION_P = "fusion_p"
        const val K_FUSION_AT = "fusion_at"
        const val K_FUSION_RTH = "fusion_rth"
        const val K_FUSION_SELF = "fusion_self"
        const val K_AMBIENT_BIAS = "ambient_bias_c"
        const val K_THEME_MODE = "theme_mode"
        const val K_DYNAMIC_COLOR = "dynamic_color"
        const val K_SEED = "seed_color"
        const val K_PURE_BLACK = "pure_black"
        const val K_CHART_SERIES = "chart_series"
    }
}
