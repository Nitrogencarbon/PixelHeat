package com.app.pixelheat.sensors

import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Process
import com.app.pixelheat.model.AppResource
import com.app.pixelheat.model.AppUsage
import com.app.pixelheat.privilege.PrivilegeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A camera client: a package name, or only a pid for native clients reported as `client.pid<N>`. */
data class CameraClient(val packageName: String?, val pid: Int?)

/**
 * Attributes resource use to apps, once per background sample.
 *
 * With usage access: foreground time per app and mobile / Wi-Fi bytes per app.
 * With Shizuku or root: per-process CPU share (`dumpsys cpuinfo`) and GPS requesters (`dumpsys location`).
 */
class AppActivityReader(context: Context, private val privilege: PrivilegeManager) {
    private val appContext = context.applicationContext
    private val usage = appContext.getSystemService(UsageStatsManager::class.java)
    private val netStats = appContext.getSystemService(NetworkStatsManager::class.java)
    private val appOps = appContext.getSystemService(AppOpsManager::class.java)
    private val pm = appContext.packageManager

    private var lastAt = 0L
    private var lastForeground: String? = null
    private var lastCameraUser: String? = null
    private var anchor = 0L
    private val previousBytes = HashMap<Pair<Int, Int>, Long>()

    fun hasUsageAccess(): Boolean = appOps.unsafeCheckOpNoThrow(
        AppOpsManager.OPSTR_GET_USAGE_STATS,
        Process.myUid(),
        appContext.packageName,
    ) == AppOpsManager.MODE_ALLOWED

    suspend fun read(now: Long, gpsActive: Boolean, cameraActive: Boolean): List<AppUsage> = withContext(Dispatchers.IO) {
        val since = if (lastAt == 0L) now - FIRST_WINDOW_MS else lastAt
        lastAt = now
        buildList {
            if (hasUsageAccess()) {
                runCatching { addAll(foreground(since, now)) }
                runCatching { addAll(traffic(now)) }
            }
            privilege.activeShell()?.let { shell ->
                shell.exec("dumpsys cpuinfo")?.let { addAll(parseCpuInfo(it)) }
                if (gpsActive) gpsUsers().forEach { add(AppUsage(AppResource.GPS, it, 1f)) }
            }
            if (cameraActive) {
                val users = cameraClients(activeOnly = true).ifEmpty { listOfNotNull(lastCameraUser) }
                users.forEach { add(AppUsage(AppResource.CAMERA, it, 1f)) }
            }
        }
    }

    /**
     * Who just opened a camera. With a privileged shell this is the camera service's own client record,
     * which also covers native processes such as Face Unlock; otherwise the app on screen is the best guess.
     */
    suspend fun cameraUser(): String? = withContext(Dispatchers.IO) {
        val user = cameraClients(activeOnly = false).firstOrNull()
            ?: if (hasUsageAccess()) runCatching { currentForeground(System.currentTimeMillis()) }.getOrNull() else null
        user?.also { lastCameraUser = it }
    }

    /** Apps holding a non-passive GPS request; empty without a privileged shell. */
    suspend fun gpsUsers(): List<String> = withContext(Dispatchers.IO) {
        val dump = privilege.activeShell()?.exec("dumpsys location", LOCATION_TIMEOUT_MS) ?: return@withContext emptyList()
        parseGpsRequesters(dump).filter(::isInstalled)
    }

    private suspend fun cameraClients(activeOnly: Boolean): List<String> {
        val shell = privilege.activeShell() ?: return emptyList()
        val dump = shell.exec("dumpsys media.camera") ?: return emptyList()
        val clients = parseActiveCameraClients(dump).ifEmpty {
            if (activeOnly) emptyList() else listOfNotNull(parseLastCameraConnect(dump))
        }
        return clients.map { client ->
            client.packageName
                ?: client.pid?.let { pid -> shell.exec("cat /proc/$pid/cmdline")?.let(::processFromCmdline) }
                ?: SYSTEM_PACKAGE
        }.distinct()
    }

    /** Package resumed most recently and not yet paused. */
    private fun currentForeground(now: Long): String? {
        val events = usage.queryEvents(now - FOREGROUND_LOOKBACK_MS, now)
        var current: String? = null
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> current = event.packageName
                UsageEvents.Event.ACTIVITY_PAUSED -> if (event.packageName == current) current = null
            }
        }
        return current?.takeIf { it != appContext.packageName && !isLauncherNoise(it) }
    }

    /** Seconds each app spent resumed in [from, to], carrying the app that was open at the last call. */
    private fun foreground(from: Long, to: Long): List<AppUsage> {
        val events = usage.queryEvents(from, to)
        val seconds = HashMap<String, Double>()
        var current = lastForeground
        var currentSince = from
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    current?.let { seconds.merge(it, (event.timeStamp - currentSince) / 1000.0, Double::plus) }
                    current = event.packageName
                    currentSince = event.timeStamp
                }
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                    if (event.eventType == UsageEvents.Event.SCREEN_NON_INTERACTIVE || event.packageName == current) {
                        current?.let { seconds.merge(it, (event.timeStamp - currentSince) / 1000.0, Double::plus) }
                        current = null
                    }
                }
            }
        }
        current?.let { seconds.merge(it, (to - currentSince) / 1000.0, Double::plus) }
        lastForeground = current
        return seconds.filter { it.value >= 1.0 && it.key != appContext.packageName && !isLauncherNoise(it.key) }
            .map { AppUsage(AppResource.FOREGROUND, it.key, it.value.toFloat()) }
    }

    /**
     * Bytes per uid since the previous call. Totals are re-read from a fixed anchor so the
     * difference between two reads is the traffic in between.
     */
    private fun traffic(now: Long): List<AppUsage> {
        val dayStart = now - now % DAY_MS
        if (dayStart != anchor) {
            anchor = dayStart
            previousBytes.clear()
        }
        val out = mutableListOf<AppUsage>()
        val first = previousBytes.isEmpty()
        for ((transport, resource) in TRANSPORTS) {
            val totals = HashMap<Int, Long>()
            netStats.querySummary(transport, null, anchor, now + 60_000L).use { stats ->
                val bucket = NetworkStats.Bucket()
                while (stats.hasNextBucket()) {
                    stats.getNextBucket(bucket)
                    totals.merge(bucket.uid, bucket.rxBytes + bucket.txBytes, Long::plus)
                }
            }
            for ((uid, bytes) in totals) {
                val key = transport to uid
                val delta = bytes - (previousBytes.put(key, bytes) ?: if (first) bytes else 0L)
                if (delta < MIN_TRAFFIC_BYTES) continue
                val pkg = packageForUid(uid) ?: continue
                out += AppUsage(resource, pkg, delta / 1024f)
            }
        }
        return out.groupBy { it.resource }.flatMap { (_, rows) -> rows.sortedByDescending { it.value }.take(MAX_PER_RESOURCE) }
    }

    private fun packageForUid(uid: Int): String? = when {
        uid == Process.myUid() -> null
        uid < Process.FIRST_APPLICATION_UID -> SYSTEM_PACKAGE
        else -> pm.getPackagesForUid(uid)?.firstOrNull()
    }

    private fun isInstalled(pkg: String): Boolean = runCatching { pm.getApplicationInfo(pkg, 0); true }.getOrDefault(false)

    private fun isLauncherNoise(pkg: String): Boolean = pkg == "com.android.systemui"

    companion object {
        /** Pseudo-package for traffic and CPU owned by Android itself. */
        const val SYSTEM_PACKAGE = "android"
        private const val FIRST_WINDOW_MS = 2 * 60_000L
        private const val DAY_MS = 24 * 60 * 60_000L
        private const val MIN_TRAFFIC_BYTES = 64 * 1024L
        private const val MAX_PER_RESOURCE = 5
        private const val LOCATION_TIMEOUT_MS = 6_000L
        private const val FOREGROUND_LOOKBACK_MS = 30 * 60_000L
        @Suppress("DEPRECATION")
        private val TRANSPORTS = listOf(
            ConnectivityManager.TYPE_MOBILE to AppResource.MOBILE_DATA,
            ConnectivityManager.TYPE_WIFI to AppResource.WIFI_DATA,
        )

        private val CPU_LINE = Regex("""^\s*([\d.]+)% (\d+)/([^:]+):""")
        private const val MIN_CPU_PCT = 1f

        /**
         * Parses `dumpsys cpuinfo` process lines (`25% 1807/system_server: ...`). Values are percent of one
         * core; they are kept as-is because only the ratio between processes matters.
         */
        fun parseCpuInfo(text: String): List<AppUsage> {
            val totals = LinkedHashMap<String, Float>()
            text.lineSequence().forEach { line ->
                val m = CPU_LINE.find(line) ?: return@forEach
                val pct = m.groupValues[1].toFloatOrNull() ?: return@forEach
                totals.merge(processOwner(m.groupValues[3].trim()), pct, Float::plus)
            }
            return totals.filter { it.value >= MIN_CPU_PCT }
                .entries.sortedByDescending { it.value }.take(MAX_PER_RESOURCE)
                .map { AppUsage(AppResource.CPU, it.key, it.value) }
        }

        /** App processes map to their package; native daemons and kernel threads to [SYSTEM_PACKAGE]. */
        fun processOwner(process: String): String {
            val base = process.substringBefore(':')
            return if ('.' in base && !base.startsWith("android.hardware") && !base.startsWith("vendor.")) base else SYSTEM_PACKAGE
        }

        private val WORK_SOURCE = Regex("""WorkSource\{([^}]*)\}""")
        private val WORK_ENTRY = Regex("""\d+ ([\w.]+)""")
        private const val PLAY_SERVICES = "com.google.android.gms"

        /**
         * Apps behind the active, non-passive requests listed under `gps provider: / listeners:` in
         * `dumpsys location`. Play Services forwards requests for other apps; when its work source
         * names them, those apps are reported instead of Play Services itself.
         */
        fun parseGpsRequesters(text: String): Set<String> {
            val lines = text.lines()
            val provider = lines.indexOfFirst { it.trim() == "gps provider:" }
            if (provider < 0) return emptySet()
            val listeners = (provider + 1 until lines.size).firstOrNull { lines[it].trim() == "listeners:" } ?: return emptySet()
            val indent = lines[listeners].indexOfFirst { !it.isWhitespace() }
            return lines.drop(listeners + 1)
                .takeWhile { it.isNotBlank() && it.indexOfFirst { c -> !c.isWhitespace() } > indent }
                .filter { "Request[" in it && "Request[PASSIVE" !in it }
                .flatMap { line ->
                    val owners = WORK_SOURCE.find(line)?.groupValues?.get(1)
                        ?.let { ws -> WORK_ENTRY.findAll(ws).map { it.groupValues[1] }.toList() }
                        .orEmpty()
                    owners.filter { it != PLAY_SERVICES }.ifEmpty { owners }
                }
                .filter { it != "android" }
                .toSet()
        }

        private val ACTIVE_CLIENT = Regex("""PID: (\d+),[^()]*?Client Package Name: ([^,\s)]+)""")
        private val CONNECT_LINE = Regex("""(\d\d-\d\d \d\d:\d\d:\d\d) : CONNECT device \S+ client for package (\S+) \(PID (\d+)\)""")
        private val NATIVE_CLIENT = Regex("""client\.pid<\d+>""")

        /** Clients listed under `Active Camera Clients:` in `dumpsys media.camera`. */
        fun parseActiveCameraClients(text: String): List<CameraClient> {
            val section = text.substringAfter("Active Camera Clients:", "").substringBefore("]")
            return ACTIVE_CLIENT.findAll(section).map { cameraClient(it.groupValues[2], it.groupValues[1]) }.toList()
        }

        /** The most recent `CONNECT` in the camera service event log, which outlives short opens like Face Unlock. */
        fun parseLastCameraConnect(text: String): CameraClient? =
            CONNECT_LINE.findAll(text).maxByOrNull { it.groupValues[1] }?.let { cameraClient(it.groupValues[2], it.groupValues[3]) }

        private fun cameraClient(pkg: String, pid: String) =
            if (NATIVE_CLIENT.matches(pkg)) CameraClient(null, pid.toIntOrNull()) else CameraClient(pkg, pid.toIntOrNull())

        /** Process name from `/proc/<pid>/cmdline` (NUL-separated, first entry is the executable). */
        fun processFromCmdline(cmdline: String): String? =
            cmdline.substringBefore('\u0000').trim().substringAfterLast('/').takeIf { it.isNotEmpty() }

        /** Friendly names for native processes that use the camera or other heat-relevant hardware. */
        fun nativeLabel(process: String): String? = when {
            "biometrics.face" in process -> "Face Unlock"
            "biometrics.fingerprint" in process -> "Fingerprint unlock"
            process == SYSTEM_PACKAGE -> "Android system"
            process.startsWith("android.hardware.") || process.startsWith("vendor.") -> "System service ($process)"
            else -> null
        }
    }
}
