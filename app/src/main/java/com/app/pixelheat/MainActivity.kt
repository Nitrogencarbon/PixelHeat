package com.app.pixelheat

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.app.pixelheat.ui.PixelHeatNavigation
import com.app.pixelheat.ui.theme.PixelHeatTheme
import com.app.pixelheat.ui.theme.isDarkTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val prefs = (application as PixelHeatApp).container.prefs
        setContent {
            val appearance by prefs.appearance.collectAsStateWithLifecycle()
            val dark = appearance.isDarkTheme()
            DisposableEffect(dark) {
                val style = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            PixelHeatTheme(appearance) {
                PixelHeatNavigation()
            }
        }
    }
}
