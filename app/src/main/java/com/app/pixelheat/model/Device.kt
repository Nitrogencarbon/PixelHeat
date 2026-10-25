package com.app.pixelheat.model

/**
 * Brief device census shown on the home summary.
 *
 * [rootModules] is null when `/data/adb` cannot be read, so a missing root shell is not shown as zero.
 * [alwaysOnApps] counts user-installed apps set to unrestricted battery use.
 */
data class DeviceReport(
    val installedApps: Int,
    val rootModules: Int?,
    val xposedModules: Int,
    val alwaysOnApps: Int,
) {
    fun summaryLine(): String {
        val modules = rootModules?.toString() ?: "—"
        return "$installedApps apps · $modules root modules · $xposedModules Xposed · $alwaysOnApps always on"
    }
}

/** Manifest keys used by classic Xposed, EdXposed, LSPosed and Vector modules. */
internal val XPOSED_METADATA_KEYS = setOf("xposedmodule", "xposedminversion", "xposeddescription")

/** APK entries used by legacy assets and libxposed API 100/101/102. */
internal val XPOSED_ZIP_ENTRIES = listOf(
    "assets/xposed_init",
    "META-INF/xposed/java_init.list",
    "META-INF/xposed/native_init.list",
)

internal fun isXposedModule(metadataKeys: Set<String>, zipEntryExists: (String) -> Boolean): Boolean {
    if (metadataKeys.any { it in XPOSED_METADATA_KEYS }) return true
    return XPOSED_ZIP_ENTRIES.any(zipEntryExists)
}

/**
 * Parses the module listing. A missing `/data/adb` is zero modules. A permission failure is unknown.
 * Module ids are unique, so the same folder mounted from Magisk and KernelSU counts once.
 */
internal fun rootModuleCount(output: String): Int? {
    val lines = output.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    val adb = lines.firstOrNull { it.startsWith("ADB:") }?.removePrefix("ADB:")?.trim() ?: return null
    if (adb != "/data/adb") {
        val denied = adb.contains("denied", ignoreCase = true) || adb.contains("permission", ignoreCase = true)
        return if (denied) null else 0
    }
    val sawBase = lines.any { it.startsWith("BASE:") }
    val deniedBase = lines.any { it.startsWith("DENY:") }
    if (deniedBase && !sawBase) return null
    return lines.asSequence()
        .filter { it.startsWith("MOD:") }
        .map { it.removePrefix("MOD:") }
        .filter { it.isNotEmpty() && '*' !in it && '/' !in it }
        .toSet()
        .size
}

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
    /** Phone temperature while the screen is on or the phone is not resting. */
    val avgInUseTemp: Float?,
)

data class MonitorStatus(
    val level: HeatLevel,
    val lastSampleAt: Long,
    val nextSampleAt: Long,
    val referenceTemp: Float,
    val referenceSource: PhoneTempSource = PhoneTempSource.BATTERY,
    val thermalStatus: Int = ThermalStatus.NONE,
)
