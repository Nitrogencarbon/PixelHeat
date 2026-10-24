package com.app.pixelheat.sensors

import com.app.pixelheat.model.ZoneCategory
import com.app.pixelheat.model.ZoneSource
import com.app.pixelheat.model.ZoneTemp
import com.app.pixelheat.model.ZoneThreshold
import com.app.pixelheat.model.ThrottleStep
import com.app.pixelheat.privilege.PrivilegeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class ThermalReading(
    val zones: List<ZoneTemp>,
    val source: ZoneSource,
    val thresholds: List<ZoneThreshold> = emptyList(),
    val headroomThresholds: List<Float?> = emptyList(),
)

class ThermalZoneReader(private val privilege: PrivilegeManager) {

    @Volatile
    private var sysfsBlockedUntil = 0L

    suspend fun read(): ThermalReading = withContext(Dispatchers.IO) {
        privilege.activeShell()?.let { shell ->
            val dump = shell.exec("dumpsys thermalservice").orEmpty()
            val thresholds = parseThresholds(dump)
            val headroom = parseHeadroomThresholds(dump)
            parseDumpsys(dump)
                .takeIf { it.isNotEmpty() }
                ?.let { return@withContext ThermalReading(it, ZoneSource.HAL_PRIVILEGED, thresholds, headroom) }
            parseSysfsDump(shell.exec(SYSFS_DUMP_CMD).orEmpty())
                .takeIf { it.isNotEmpty() }
                ?.let { return@withContext ThermalReading(it, ZoneSource.HAL_PRIVILEGED, thresholds, headroom) }
        }

        val now = System.currentTimeMillis()
        if (now >= sysfsBlockedUntil) {
            val zones = readSysfsDirect()
            if (zones.isNotEmpty()) return@withContext ThermalReading(zones, ZoneSource.SYSFS)
            // SELinux blocks thermal sysfs for untrusted apps on most Pixels: don't retry every sample.
            sysfsBlockedUntil = now + SYSFS_RETRY_MS
        }
        ThermalReading(emptyList(), ZoneSource.PUBLIC_API)
    }

    private fun readSysfsDirect(): List<ZoneTemp> {
        val dirs = File(SYSFS_ROOT).listFiles { f -> f.name.startsWith("thermal_zone") } ?: return emptyList()
        return dirs.mapNotNull { dir ->
            val type = runCatching { File(dir, "type").readText().trim() }.getOrNull() ?: return@mapNotNull null
            val raw = runCatching { File(dir, "temp").readText().trim().toLong() }.getOrNull() ?: return@mapNotNull null
            sysfsZone(type, raw)
        }.distinctBy { it.name }
    }

    companion object {
        private const val SYSFS_ROOT = "/sys/class/thermal"
        private const val SYSFS_RETRY_MS = 10 * 60_000L
        private const val SYSFS_DUMP_CMD =
            "for z in $SYSFS_ROOT/thermal_zone*; do echo \"\$(cat \$z/type)|\$(cat \$z/temp)\"; done"

        private val TEMPERATURE_REGEX = Regex(
            """Temperature\{mValue=(-?[\d.]+|NaN), mType=(-?\d+), mName=([^,}]+)(?:, mStatus=(-?\d+))?\}""",
        )
        private val THRESHOLD_REGEX = Regex(
            """TemperatureThreshold\{mType=(-?\d+), mName=([^,]+), mHotThrottlingThresholds=\[([^\]]*)\], mColdThrottlingThresholds=\[([^\]]*)\]\}""",
        )
        private val NON_TEMPERATURE_ZONES = listOf("vdroop", "oilo", "ocp", "smpl", "bcl", "pmic", "lvl", "ibat", "vbat")

        /** Parses the "Current temperatures from HAL" section of `dumpsys thermalservice`. */
        fun parseDumpsys(text: String): List<ZoneTemp> {
            if (text.isBlank()) return emptyList()
            val lines = text.lines()
            val header = lines.indexOfFirst { it.contains("Current temperatures from HAL") }
            val section = if (header >= 0) {
                lines.drop(header + 1).takeWhile { it.contains("Temperature{") }
            } else {
                lines
            }
            return section.mapNotNull { line ->
                val m = TEMPERATURE_REGEX.find(line) ?: return@mapNotNull null
                val value = m.groupValues[1].toFloatOrNull()?.takeIf { !it.isNaN() } ?: return@mapNotNull null
                val name = m.groupValues[3].trim()
                val halCategory = ZoneCategory.fromHalType(m.groupValues[2].toInt()) ?: return@mapNotNull null
                if (value !in -30f..150f) return@mapNotNull null
                // Virtual "UI" sensors such as VIRTUAL-USB-UI are warning flags that sit at exactly 0.
                if (value == 0f && name.startsWith("VIRTUAL", ignoreCase = true)) return@mapNotNull null
                val category = if (halCategory == ZoneCategory.OTHER && ZoneCategory.fromName(name) == ZoneCategory.USB) {
                    ZoneCategory.USB
                } else {
                    halCategory
                }
                ZoneTemp(
                    name = name,
                    category = category,
                    celsius = value,
                    halStatus = m.groupValues[4].toIntOrNull(),
                )
            }.associateBy { it.name }.values.toList()
        }

        /**
         * Parses "Temperature static thresholds from HAL". Values below 15 °C are status hints, not
         * temperatures, and are dropped.
         */
        fun parseThresholds(text: String): List<ZoneThreshold> {
            if (text.isBlank()) return emptyList()
            val lines = text.lines()
            val header = lines.indexOfFirst { it.contains("Temperature static thresholds") }
            val section = if (header >= 0) lines.drop(header + 1).takeWhile { it.contains("TemperatureThreshold") } else lines
            return section.mapNotNull { line ->
                val m = THRESHOLD_REGEX.find(line) ?: return@mapNotNull null
                val type = m.groupValues[1].toInt()
                val name = m.groupValues[2].trim()
                val hot = throttleSteps(m.groupValues[3])
                val cold = throttleSteps(m.groupValues[4])
                if (hot.isEmpty() && cold.isEmpty()) return@mapNotNull null
                ZoneThreshold(name, thresholdCategory(type, name), hot, cold)
            }
        }

        fun parseHeadroomThresholds(text: String): List<Float?> {
            val lines = text.lines()
            val header = lines.indexOfFirst { it.contains("Temperature headroom thresholds") }
            val raw = lines.getOrNull(header + 1)?.trim().orEmpty().removePrefix("[").removeSuffix("]")
            if (raw.isEmpty() || !raw.contains(',')) return emptyList()
            return raw.split(',').map { it.trim().toFloatOrNull()?.takeIf { v -> !v.isNaN() } }
        }

        private fun throttleSteps(csv: String): List<ThrottleStep> = csv.split(',').mapIndexedNotNull { index, raw ->
            val value = raw.trim().toFloatOrNull()?.takeIf { !it.isNaN() && it in 15f..150f } ?: return@mapIndexedNotNull null
            ThrottleStep(index, value)
        }

        private fun thresholdCategory(type: Int, name: String): ZoneCategory? {
            ZoneCategory.fromHalType(type)?.let { return it }
            val lower = name.lowercase()
            if ("charg" in lower) return ZoneCategory.BATTERY
            return ZoneCategory.fromName(name).takeIf { it != ZoneCategory.OTHER }
        }

        /** Parses lines formatted as `type|millidegrees`. */
        fun parseSysfsDump(text: String): List<ZoneTemp> = text.lines().mapNotNull { line ->
            val parts = line.split('|')
            if (parts.size != 2) return@mapNotNull null
            val raw = parts[1].trim().toLongOrNull() ?: return@mapNotNull null
            sysfsZone(parts[0].trim(), raw)
        }.distinctBy { it.name }

        private fun sysfsZone(type: String, raw: Long): ZoneTemp? {
            val lower = type.lowercase()
            if (type.isEmpty() || lower == "soc" || NON_TEMPERATURE_ZONES.any { it in lower }) return null
            val celsius = if (raw >= 1000 || raw <= -1000) raw / 1000f else raw.toFloat()
            if (celsius !in -30f..150f || celsius == 0f) return null
            return ZoneTemp(type, ZoneCategory.fromName(type), celsius)
        }
    }
}
