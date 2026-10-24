package com.app.pixelheat.model

enum class ThemeMode(val label: String) {
    SYSTEM("System"),
    LIGHT("Light"),
    DARK("Dark"),
}

data class Appearance(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** Material You colors extracted from the wallpaper; [seedArgb] is used when off. */
    val dynamicColor: Boolean = true,
    val seedArgb: Int = DEFAULT_SEED,
    /** Black backgrounds in dark theme, for OLED panels. */
    val pureBlack: Boolean = false,
) {
    companion object {
        const val DEFAULT_SEED: Int = 0xFFFF6E40.toInt()
    }
}
