package com.app.pixelheat.ui.settings

import android.app.Application
import android.app.UiModeManager
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.app.pixelheat.PixelHeatApp
import com.app.pixelheat.data.archive.ArchiveContents
import com.app.pixelheat.model.Appearance
import com.app.pixelheat.model.DeviceIdentity
import com.app.pixelheat.model.MonitorStatus
import com.app.pixelheat.model.PrivilegeState
import com.app.pixelheat.model.ThemeMode
import com.app.pixelheat.service.ThermalMonitorService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** A backup the user picked, shown for confirmation before it replaces the measurements. */
data class ImportPreview(val samples: Int, val exportedAt: Long)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val c = (application as PixelHeatApp).container

    val appearance: StateFlow<Appearance> = c.prefs.appearance
    val privilege: StateFlow<PrivilegeState> = c.privilege.state
    val monitorStatus: StateFlow<MonitorStatus?> = ThermalMonitorService.status
    val monitoringEnabled: StateFlow<Boolean> = c.prefs.monitoringEnabled
    val weatherConsent: StateFlow<Boolean> = c.prefs.weatherConsent
    val autoExport: StateFlow<Boolean> = c.prefs.autoExport
    val lastExportAt: StateFlow<Long> = c.prefs.lastExportAt

    private val _dataBusy = MutableStateFlow(false)
    val dataBusy: StateFlow<Boolean> = _dataBusy.asStateFlow()

    private val _dataMessage = MutableStateFlow<String?>(null)
    val dataMessage: StateFlow<String?> = _dataMessage.asStateFlow()

    private var pendingContents: ArchiveContents? = null
    private val _pendingImport = MutableStateFlow<ImportPreview?>(null)
    val pendingImport: StateFlow<ImportPreview?> = _pendingImport.asStateFlow()
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

    fun setPureBlack(enabled: Boolean) = c.prefs.updateAppearance { it.copy(pureBlack = enabled) }

    fun setMonitoring(enabled: Boolean) = c.monitor.setEnabled(enabled)

    fun setWeatherConsent(granted: Boolean) = c.prefs.setWeatherConsent(granted)

    fun setAutoExport(enabled: Boolean) {
        c.prefs.setAutoExport(enabled)
        if (enabled) {
            c.exportScheduler.enqueue(replace = true)
            exportNow()
        } else {
            c.exportScheduler.cancel()
        }
    }

    fun exportNow() = dataAction(
        work = { c.archive.export() },
        success = { "Exported $it samples to Downloads/PixelHeat" },
    )

    fun stageImport(uri: Uri) {
        if (_dataBusy.value) return
        viewModelScope.launch {
            _dataBusy.value = true
            _dataMessage.value = null
            runCatching { c.archive.preview(uri) }
                .onSuccess { contents ->
                    pendingContents = contents
                    _pendingImport.value = ImportPreview(contents.samples.size, contents.exportedAt)
                }
                .onFailure { _dataMessage.value = it.message ?: "Couldn't read that file" }
            _dataBusy.value = false
        }
    }

    fun confirmImport() {
        val contents = pendingContents ?: return
        pendingContents = null
        _pendingImport.value = null
        dataAction(
            work = { c.archive.restore(contents); contents.samples.size },
            success = { "Imported $it samples" },
        )
    }

    fun cancelImport() {
        pendingContents = null
        _pendingImport.value = null
    }

    fun resetCollected() = dataAction(
        work = { c.archive.clear(); 0 },
        success = { "Collected data deleted" },
    )

    suspend fun collectedSampleCount() = c.repository.sampleCount()

    private fun <T> dataAction(work: suspend () -> T, success: (T) -> String) {
        if (_dataBusy.value) return
        viewModelScope.launch {
            _dataBusy.value = true
            _dataMessage.value = null
            _dataMessage.value = runCatching { work() }.fold(
                onSuccess = success,
                onFailure = { it.message ?: "Something went wrong" },
            )
            _dataBusy.value = false
        }
    }

    fun requestRoot() {
        viewModelScope.launch { c.privilege.requestRoot() }
    }

    fun requestShizuku() = c.privilege.requestShizuku()
}
