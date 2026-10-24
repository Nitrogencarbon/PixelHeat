package com.app.pixelheat.sensors

import com.app.pixelheat.privilege.PrivilegeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Average current/max frequency ratio across online cores, as a 0-100 load proxy. */
class CpuFreqReader(private val privilege: PrivilegeManager) {
    private val maxFreq = ConcurrentHashMap<String, Long>()

    @Volatile
    private var directReadable: Boolean? = null

    suspend fun readPercent(): Float? = withContext(Dispatchers.IO) {
        if (directReadable != false) {
            val direct = readDirect()
            directReadable = direct != null
            if (direct != null) return@withContext direct
        }
        readPrivileged()
    }

    private fun readDirect(): Float? {
        val cpus = File(CPU_ROOT).listFiles { f -> CPU_DIR.matches(f.name) } ?: return null
        val ratios = cpus.mapNotNull { dir ->
            val cur = readLong(File(dir, "cpufreq/scaling_cur_freq")) ?: return@mapNotNull null
            val max = maxFreq.getOrPut(dir.name) { readLong(File(dir, "cpufreq/cpuinfo_max_freq")) ?: 0L }
            if (max > 0) cur.toFloat() / max else null
        }
        return if (ratios.isEmpty()) null else (ratios.average() * 100).toFloat().coerceIn(0f, 100f)
    }

    private suspend fun readPrivileged(): Float? {
        val out = privilege.activeShell()?.exec(PRIVILEGED_CMD) ?: return null
        val ratios = out.lines().mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"))
            val cur = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
            val max = parts.getOrNull(1)?.toLongOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
            cur.toFloat() / max
        }
        return if (ratios.isEmpty()) null else (ratios.average() * 100).toFloat().coerceIn(0f, 100f)
    }

    private fun readLong(file: File): Long? = runCatching { file.readText().trim().toLong() }.getOrNull()

    private companion object {
        const val CPU_ROOT = "/sys/devices/system/cpu"
        val CPU_DIR = Regex("cpu\\d+")
        const val PRIVILEGED_CMD =
            "for c in $CPU_ROOT/cpu[0-9]*; do echo \"\$(cat \$c/cpufreq/scaling_cur_freq) \$(cat \$c/cpufreq/cpuinfo_max_freq)\"; done"
    }
}
