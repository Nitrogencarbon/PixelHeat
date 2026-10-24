package com.app.pixelheat.sensors

import com.app.pixelheat.model.CpuCluster
import com.app.pixelheat.model.DeviceSpecs
import com.app.pixelheat.privilege.PrivilegeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Reads unchanging hardware facts once, directly or through the privileged shell. */
class DeviceSpecsReader(private val privilege: PrivilegeManager) {
    @Volatile
    private var cached: DeviceSpecs? = null
    private var nextTryAt = 0L

    suspend fun get(now: Long = System.currentTimeMillis()): DeviceSpecs {
        cached?.let { return it }
        if (now < nextTryAt) return DeviceSpecs.EMPTY
        val specs = withContext(Dispatchers.IO) { readDirect() ?: readPrivileged() }
        return if (specs != null && specs.hasAny()) {
            cached = specs
            specs
        } else {
            nextTryAt = now + RETRY_MS
            DeviceSpecs.EMPTY
        }
    }

    private fun readDirect(): DeviceSpecs? = runCatching {
        val battery = File("/sys/class/power_supply/battery")
        val clusters = readCpuClusters()
        val (gpuMin, gpuMax) = readGpuRange()
        DeviceSpecs(
            batteryChemistry = readText(File(battery, "technology")),
            designCapacityUAh = readText(File(battery, "charge_full_design"))?.toIntOrNull(),
            fullCapacityUAh = readText(File(battery, "charge_full"))?.toIntOrNull(),
            cpuClusters = clusters,
            gpuMinHz = gpuMin,
            gpuMaxHz = gpuMax,
        ).takeIf { it.hasAny() }
    }.getOrNull()

    private suspend fun readPrivileged(): DeviceSpecs? {
        val out = privilege.activeShell()?.exec(PRIVILEGED_CMD) ?: return null
        val cpuMax = mutableListOf<Long>()
        var chemistry: String? = null
        var design: Int? = null
        var full: Int? = null
        var gpu: List<Long> = emptyList()
        out.lines().forEach { line ->
            val parts = line.trim().split(Regex("\\s+"))
            when (parts.firstOrNull()) {
                "BAT" -> {
                    chemistry = parts.getOrNull(1)?.takeIf { it.isNotBlank() && it != "na" }
                    design = parts.getOrNull(2)?.toIntOrNull()
                    full = parts.getOrNull(3)?.toIntOrNull()
                }
                "CPU" -> parts.getOrNull(1)?.toLongOrNull()?.let { cpuMax += it }
                "GPU" -> gpu = parts.drop(1).mapNotNull { it.toLongOrNull() }
            }
        }
        return DeviceSpecs(
            batteryChemistry = chemistry,
            designCapacityUAh = design,
            fullCapacityUAh = full,
            cpuClusters = clustersOf(cpuMax),
            gpuMinHz = gpu.minOrNull(),
            gpuMaxHz = gpu.maxOrNull(),
        ).takeIf { it.hasAny() }
    }

    private fun readCpuClusters(): List<CpuCluster> {
        val root = File(CPU_ROOT).listFiles { f -> CPU_DIR.matches(f.name) } ?: return emptyList()
        val maxes = root.mapNotNull { readText(File(it, "cpufreq/cpuinfo_max_freq"))?.toLongOrNull() }
        return clustersOf(maxes)
    }

    private fun readGpuRange(): Pair<Long?, Long?> {
        val dir = File("/sys/class/devfreq").listFiles()?.firstOrNull { "gpu" in it.name.lowercase() } ?: return null to null
        val available = readText(File(dir, "available_frequencies"))
            ?.split(Regex("\\s+"))
            ?.mapNotNull { it.toLongOrNull() }
            .orEmpty()
        if (available.isNotEmpty()) return available.min() to available.max()
        return null to readText(File(dir, "max_freq"))?.toLongOrNull()
    }

    private fun readText(file: File): String? =
        runCatching { file.readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }

    private fun clustersOf(maxKHz: List<Long>): List<CpuCluster> =
        maxKHz.groupingBy { it }.eachCount().entries
            .sortedBy { it.key }
            .map { CpuCluster(it.value, it.key) }

    private fun DeviceSpecs.hasAny(): Boolean =
        batteryChemistry != null || designCapacityUAh != null || cpuClusters.isNotEmpty() || gpuMaxHz != null

    private companion object {
        const val CPU_ROOT = "/sys/devices/system/cpu"
        const val RETRY_MS = 10 * 60_000L
        val CPU_DIR = Regex("cpu\\d+")
        const val PRIVILEGED_CMD =
            "echo BAT \$(cat /sys/class/power_supply/battery/technology) " +
                "\$(cat /sys/class/power_supply/battery/charge_full_design) " +
                "\$(cat /sys/class/power_supply/battery/charge_full); " +
                "for c in $CPU_ROOT/cpu[0-9]*; do echo CPU \$(cat \$c/cpufreq/cpuinfo_max_freq); done; " +
                "for d in /sys/class/devfreq/*gpu*; do echo GPU \$(cat \$d/available_frequencies); done"
    }
}
