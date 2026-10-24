package com.app.pixelheat.ui.settings

import android.app.Application
import android.app.UiModeManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.app.pixelheat.PixelHeatApp
import com.app.pixelheat.model.Appearance
import com.app.pixelheat.model.DeviceIdentity
import com.app.pixelheat.model.MonitorStatus
import com.app.pixelheat.model.PrivilegeState
import com.app.pixelheat.model.ThemeMode
import com.app.pixelheat.service.ThermalMonitorService
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val c = (application as PixelHeatApp).container

    val appearance: StateFlow<Appearance> = c.prefs.appearance
    val privilege: StateFlow<PrivilegeState> = c.privilege.state
    val monitorStatus: StateFlow<MonitorStatus?> = ThermalMonitorService.status
    val monitoringEnabled: StateFlow<Boolean> = c.prefs.monitoringEnabled
    val identity: DeviceIdentity = c.deviceInfo.identity()
    val versionName: String = runCatching {
        application.packageManager.getPackageInfo(application.packageName, 0).versionName
    }.getOrNull() ?: "—"

    fun setThemeMode(mode: ThemeMode) {
        c.prefs.updateAppearance { it.copy(themeMode = mode) }
        // Lets the system apply the choice to the splash screen, window background and system bars too.
        getApplication<Application>().getSystemService(UiModeManager::class.java).setApplicationNightMode(
            when (mode) {
                ThemeMode.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
                ThemeMode.LIGHT -> UiModeManager.MODE_NIGHT_NO
                ThemeMode.DARK -> UiModeManager.MODE_NIGHT_YES
            },
        )
    }

    fun setDynamicColor(enabled: Boolean) = c.prefs.updateAppearance { it.copy(dynamicColor = enabled) }

    fun setSeed(argb: Int) = c.prefs.updateAppearance { it.copy(seedArgb = argb, dynamicColor = false) }

    fun setPureBlack(enabled: Boolean) = c.prefs.updateAppearance { it.copy(pureBlack = enabled) }

    fun setMonitoring(enabled: Boolean) = c.monitor.setEnabled(enabled)

    fun requestRoot() {
        viewModelScope.launch { c.privilege.requestRoot() }
    }

    fun requestShizuku() = c.privilege.requestShizuku()
}
