package com.app.pixelheat.model

data class DeviceIdentity(
    val manufacturer: String,
    val model: String,
    val codename: String,
    val soc: String?,
    val androidVersion: String,
    val sdkInt: Int,
    val securityPatch: String,
    val buildId: String,
    val installedAt: Long,
)

enum class PrivilegeKind(val label: String) { ROOT("Root"), SHIZUKU("Shizuku") }

enum class RootStatus(val label: String) {
    UNAVAILABLE("Not detected"),
    AVAILABLE("Available"),
    GRANTED("Active"),
    DENIED("Denied"),
}

enum class ShizukuStatus(val label: String) {
    NOT_RUNNING("Not running"),
    UNSUPPORTED("Outdated"),
    PERMISSION_REQUIRED("Permission needed"),
    DENIED("Denied"),
    GRANTED("Granted"),
    CONNECTED("Connected"),
}

data class PrivilegeState(
    val root: RootStatus = RootStatus.UNAVAILABLE,
    val shizuku: ShizukuStatus = ShizukuStatus.NOT_RUNNING,
    val shizukuUid: Int? = null,
    val active: PrivilegeKind? = null,
)

data class LifetimeAverages(
    val since: Long,
    val monitoredHours: Double,
    val samples: Long,
    val avgPhoneTemp: Float?,
    val avgMaxTemp: Float?,
    val avgAmbient: Float?,
    val peakTemp: Float?,
    val peakAt: Long?,
    val chargingPct: Float,
    val hotPct: Float,
    val throttledPct: Float,
    val screenOnPct: Float,
    val avgDischargeW: Float?,
)

data class MonitorStatus(
    val level: HeatLevel,
    val lastSampleAt: Long,
    val nextSampleAt: Long,
    val referenceTemp: Float,
    val referenceSource: PhoneTempSource = PhoneTempSource.BATTERY,
    val thermalStatus: Int = ThermalStatus.NONE,
)
