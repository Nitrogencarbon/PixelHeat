package com.app.pixelheat.model

enum class ThemeMode(val label: String) {
    SYSTEM("System"),
    LIGHT("Light"),
    DARK("Dark"),
}

data class Appearance(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** Black backgrounds in dark theme, for OLED panels. */
    val pureBlack: Boolean = false,
)
