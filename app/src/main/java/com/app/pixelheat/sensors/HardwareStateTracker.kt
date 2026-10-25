package com.app.pixelheat.sensors

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.location.GnssStatus
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.app.pixelheat.data.ThermalRepository
import com.app.pixelheat.model.HardwareEventType
import com.app.pixelheat.model.NetworkType
import com.app.pixelheat.model.ThermalSnapshot
import com.app.pixelheat.model.ThermalStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.abs

data class HardwareState(
    val screenOn: Boolean = true,
    val gpsActive: Boolean = false,
    val cameraActive: Boolean = false,
    val networkType: NetworkType = NetworkType.NONE,
    val thermalStatus: Int = ThermalStatus.NONE,
    val charging: Boolean = false,
    val fastCharging: Boolean = false,
)

/**
 * Event-driven view of the components that heat the phone. Everything here is a system
 * callback: nothing polls, so the CPU is never woken just to check state. Each transition
 * is persisted as a [HardwareEventType] and emitted on [triggers] so the monitor can take
 * an immediate sample and capture the thermal effect.
 */
class HardwareStateTracker(
    context: Context,
    private val repository: ThermalRepository,
    private val batteryReader: BatteryReader,
    private val scope: CoroutineScope,
    /** Names the package (or native process) behind a camera open / GPS start, if it can be found. */
    private val eventOwner: suspend (HardwareEventType) -> String? = { null },
    private val labelOf: (String) -> String = { it },
) {
    private val appContext = context.applicationContext
    private val powerManager = appContext.getSystemService(PowerManager::class.java)
    private val cameraManager = appContext.getSystemService(CameraManager::class.java)
    private val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
    private val locationManager = appContext.getSystemService(LocationManager::class.java)
    private val executor = ContextCompat.getMainExecutor(appContext)

    private val _state = MutableStateFlow(HardwareState())
    val state: StateFlow<HardwareState> = _state.asStateFlow()

    private val _triggers = MutableSharedFlow<HardwareEventType>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val triggers: SharedFlow<HardwareEventType> = _triggers.asSharedFlow()

    private val lock = Any()
    private var refCount = 0
    private var gnssRegistered = false
    private var batteryInitialized = false
    private var networkInitialized = false
    private var lastTriggerTemp = Float.NaN
    private val busyCameras = mutableSetOf<String>()

    private var burstActive = false
    private var brightActive = false
    private var sunActive = false

    fun acquire() = synchronized(lock) {
        if (refCount++ == 0) register()
    }

    fun release() = synchronized(lock) {
        if (refCount > 0 && --refCount == 0) unregister()
    }

    /** Call after the user grants location so GNSS tracking can start without a restart. */
    fun refreshPermissions() = synchronized(lock) {
        if (refCount > 0 && !gnssRegistered) registerGnss()
    }

    /** Derives threshold events from values only known at sampling time. */
    fun onSample(s: ThermalSnapshot) {
        val total = s.network.totalKBps
        if (!burstActive && total >= BURST_ON_KBPS) {
            burstActive = true
            emit(HardwareEventType.DATA_BURST, true, total)
        } else if (burstActive && total < BURST_OFF_KBPS) {
            burstActive = false
            emit(HardwareEventType.DATA_BURST, false)
        }

        val brightness = s.brightnessPct ?: 0
        if (!brightActive && s.screenOn && brightness >= BRIGHT_ON_PCT) {
            brightActive = true
            emit(HardwareEventType.BRIGHTNESS, true, brightness.toFloat())
        } else if (brightActive && (!s.screenOn || brightness < BRIGHT_OFF_PCT)) {
            brightActive = false
            emit(HardwareEventType.BRIGHTNESS, false, brightness.toFloat())
        }

        val lux = s.environment?.lux ?: return
        if (!sunActive && lux >= SUN_ON_LUX) {
            sunActive = true
            emit(HardwareEventType.SUNLIGHT, true, lux)
        } else if (sunActive && lux < SUN_OFF_LUX) {
            sunActive = false
            emit(HardwareEventType.SUNLIGHT, false, lux)
        }
    }

    private fun register() {
        _state.update {
            it.copy(screenOn = powerManager.isInteractive, thermalStatus = powerManager.currentThermalStatus)
        }
        ContextCompat.registerReceiver(
            appContext,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        ContextCompat.registerReceiver(
            appContext,
            batteryReceiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        powerManager.addThermalStatusListener(executor, thermalListener)
        cameraManager.registerAvailabilityCallback(executor, cameraCallback)
        runCatching { connectivity.registerDefaultNetworkCallback(networkCallback) }
        registerGnss()
    }

    private fun unregister() {
        runCatching { appContext.unregisterReceiver(screenReceiver) }
        runCatching { appContext.unregisterReceiver(batteryReceiver) }
        runCatching { powerManager.removeThermalStatusListener(thermalListener) }
        runCatching { cameraManager.unregisterAvailabilityCallback(cameraCallback) }
        runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
        if (gnssRegistered) {
            runCatching { locationManager.unregisterGnssStatusCallback(gnssCallback) }
            gnssRegistered = false
        }
        batteryInitialized = false
        networkInitialized = false
        busyCameras.clear()
    }

    @SuppressLint("MissingPermission")
    private fun registerGnss() {
        val granted = ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return
        gnssRegistered = try {
            locationManager.registerGnssStatusCallback(executor, gnssCallback)
        } catch (_: SecurityException) {
            false
        }
    }

    private fun emit(type: HardwareEventType, active: Boolean, value: Float? = null) {
        val now = System.currentTimeMillis()
        scope.launch(Dispatchers.IO) {
            val owner = if (active && type in OWNED_EVENTS) {
                delay(OWNER_LOOKUP_DELAY_MS)
                runCatching { eventOwner(type) }.getOrNull()
            } else {
                null
            }
            // This scope has no exception handler: a failed write would take the whole process down.
            try {
                repository.recordEvent(type, active, value, now, owner, owner?.let(labelOf))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
        _triggers.tryEmit(type)
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val on = intent.action == Intent.ACTION_SCREEN_ON
            if (_state.value.screenOn == on) return
            _state.update { it.copy(screenOn = on) }
            emit(HardwareEventType.SCREEN, on)
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val info = batteryReader.parse(intent)
            val previous = _state.value
            _state.update { it.copy(charging = info.isCharging, fastCharging = info.fastCharging) }
            if (!batteryInitialized) {
                batteryInitialized = true
                lastTriggerTemp = info.tempC
                return
            }
            if (info.isCharging != previous.charging) {
                emit(HardwareEventType.CHARGING, info.isCharging, info.chargerMaxW?.takeIf { info.isCharging })
            }
            if (info.fastCharging != previous.fastCharging) {
                emit(HardwareEventType.FAST_CHARGING, info.fastCharging, info.powerW.takeIf { info.fastCharging })
            }
            if (!lastTriggerTemp.isNaN() && abs(info.tempC - lastTriggerTemp) >= TEMP_TRIGGER_DELTA_C) {
                lastTriggerTemp = info.tempC
                _triggers.tryEmit(HardwareEventType.THERMAL)
            }
        }
    }

    private val thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
        val previous = _state.value.thermalStatus
        if (status == previous) return@OnThermalStatusChangedListener
        _state.update { it.copy(thermalStatus = status) }
        emit(HardwareEventType.THERMAL, status > previous, status.toFloat())
    }

    private val cameraCallback = object : CameraManager.AvailabilityCallback() {
        override fun onCameraUnavailable(cameraId: String) = updateCamera { busyCameras.add(cameraId) }
        override fun onCameraAvailable(cameraId: String) = updateCamera { busyCameras.remove(cameraId) }
    }

    private fun updateCamera(change: () -> Unit) {
        val active = synchronized(busyCameras) {
            change()
            busyCameras.isNotEmpty()
        }
        if (_state.value.cameraActive == active) return
        _state.update { it.copy(cameraActive = active) }
        emit(HardwareEventType.CAMERA, active)
    }

    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onStarted() = setGps(true)
        override fun onStopped() = setGps(false)
    }

    private fun setGps(active: Boolean) {
        if (_state.value.gpsActive == active) return
        _state.update { it.copy(gpsActive = active) }
        emit(HardwareEventType.GPS, active)
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
            setNetwork(NetworkReader.typeOf(caps))

        override fun onLost(network: Network) = setNetwork(NetworkType.NONE)
    }

    private fun setNetwork(type: NetworkType) {
        val previous = _state.value.networkType
        if (previous == type && networkInitialized) return
        _state.update { it.copy(networkType = type) }
        if (!networkInitialized) {
            networkInitialized = true
            return
        }
        emit(HardwareEventType.NETWORK, type != NetworkType.NONE, type.ordinal.toFloat())
    }

    private companion object {
        const val TEMP_TRIGGER_DELTA_C = 0.8f
        const val BURST_ON_KBPS = 1024f
        const val BURST_OFF_KBPS = 256f
        const val BRIGHT_ON_PCT = 80
        const val BRIGHT_OFF_PCT = 70
        const val SUN_ON_LUX = 25_000f
        const val SUN_OFF_LUX = 10_000f
        val OWNED_EVENTS = setOf(HardwareEventType.CAMERA, HardwareEventType.GPS)

        /** Lets the camera / location service register the new client before it is queried. */
        const val OWNER_LOOKUP_DELAY_MS = 400L
    }
}
