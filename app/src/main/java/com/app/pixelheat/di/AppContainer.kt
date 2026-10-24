package com.app.pixelheat.di

import android.content.Context
import com.app.pixelheat.data.ThermalRepository
import com.app.pixelheat.data.db.PixelHeatDatabase
import com.app.pixelheat.data.prefs.AppPreferences
import com.app.pixelheat.diagnostics.CauseEffectAnalyzer
import com.app.pixelheat.model.HardwareEventType
import com.app.pixelheat.privilege.PrivilegeManager
import com.app.pixelheat.sensors.AmbientEstimator
import com.app.pixelheat.sensors.AppActivityReader
import com.app.pixelheat.sensors.AppLabels
import com.app.pixelheat.sensors.BatteryReader
import com.app.pixelheat.sensors.CpuFreqReader
import com.app.pixelheat.sensors.DeviceInfoProvider
import com.app.pixelheat.sensors.DeviceSpecsReader
import com.app.pixelheat.sensors.EnvironmentSensors
import com.app.pixelheat.sensors.HardwareStateTracker
import com.app.pixelheat.sensors.NetworkReader
import com.app.pixelheat.sensors.PassiveLocationSource
import com.app.pixelheat.sensors.SnapshotCollector
import com.app.pixelheat.sensors.ThermalZoneReader
import com.app.pixelheat.sensors.WeatherClient
import com.app.pixelheat.service.MonitorController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Process-wide singletons, built once in [com.app.pixelheat.PixelHeatApp]. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val prefs = AppPreferences(appContext)
    private val database = PixelHeatDatabase.build(appContext)
    val repository = ThermalRepository(database.thermalDao())
    val privilege = PrivilegeManager(appContext, prefs, appScope)

    private val batteryReader = BatteryReader(appContext)
    val appActivity = AppActivityReader(appContext, privilege)
    private val appLabels = AppLabels(appContext)
    val tracker = HardwareStateTracker(
        appContext,
        repository,
        batteryReader,
        appScope,
        eventOwner = { type ->
            when (type) {
                HardwareEventType.CAMERA -> appActivity.cameraUser()
                HardwareEventType.GPS -> appActivity.gpsUsers().firstOrNull()
                else -> null
            }
        },
        labelOf = appLabels::labelOf,
    )
    val passiveLocation = PassiveLocationSource(appContext)

    val collector = SnapshotCollector(
        context = appContext,
        batteryReader = batteryReader,
        zoneReader = ThermalZoneReader(privilege),
        cpuReader = CpuFreqReader(privilege),
        specsReader = DeviceSpecsReader(privilege),
        networkReader = NetworkReader(appContext),
        environment = EnvironmentSensors(appContext),
        location = passiveLocation,
        weather = WeatherClient(prefs),
        ambientEstimator = AmbientEstimator(prefs),
        tracker = tracker,
    )

    val analyzer = CauseEffectAnalyzer(appLabels::labelOf)
    val deviceInfo = DeviceInfoProvider(appContext)
    val monitor = MonitorController(appContext, prefs)
}
