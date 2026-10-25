package com.app.pixelheat.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import com.app.pixelheat.model.Appearance
import com.app.pixelheat.model.ThemeMode
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.rememberDynamicColorScheme

@Composable
fun Appearance.isDarkTheme(): Boolean = when (themeMode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PixelHeatTheme(
    appearance: Appearance = Appearance(),
    content: @Composable () -> Unit,
) {
    val dark = appearance.isDarkTheme()
    val pureBlack = appearance.pureBlack && dark
    val colorScheme = rememberDynamicColorScheme(
        seedColor = BrandGreen,
        isDark = dark,
        isAmoled = pureBlack,
        specVersion = ColorSpec.SpecVersion.SPEC_2025,
    )

    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
        shapes = Shapes(),
        typography = Typography,
        content = content,
    )
}