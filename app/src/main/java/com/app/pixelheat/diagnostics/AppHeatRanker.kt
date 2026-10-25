package com.app.pixelheat.diagnostics

import com.app.pixelheat.data.db.AppUsageEntity
import com.app.pixelheat.data.db.ThermalSampleEntity
import com.app.pixelheat.model.AppResource
import com.app.pixelheat.model.NetworkType
import kotlin.math.max

/**
 * Ranks apps by the heat attributed to them over a whole window.
 *
 * Each sample's estimated watts for CPU, display, network, GPS and camera are split across the apps
 * using that resource, then multiplied by the seconds the sample covers. The share is of that total,
 * so an app that runs the whole time ranks above one that only appears at a temperature peak.
 */
internal object AppHeatRanker {
    private val sources = listOf(
        HeatCause.CPU_LOAD,
        HeatCause.SCREEN,
        HeatCause.NETWORK,
        HeatCause.GPS,
        HeatCause.CAMERA,
    )

    fun rank(
        samples: List<ThermalSampleEntity>,
        apps: List<AppUsageEntity>,
        from: Long,
        to: Long,
        labelOf: (String) -> String = { it },
    ): AppHeatRanking {
        val window = samples.filter { it.timestamp in from..to }.sortedBy { it.timestamp }
        val coveredMs = if (window.size >= 2) window.last().timestamp - window.first().timestamp else 0L
        val byTime = apps.groupBy { it.timestamp }
        val energy = HashMap<String, DoubleArray>()
        var observedSec = 0.0
        for (sample in window) {
            val sec = coveredSeconds(sample, from)
            if (sec <= 0.0) continue
            observedSec += sec
            val at = byTime[sample.timestamp].orEmpty()
            sources.forEachIndexed { index, cause ->
                val watts = HeatModel.watts(cause, sample)
                if (watts <= 0.0) return@forEachIndexed
                split(energy, index, rowsFor(cause, sample, at), watts * sec)
            }
        }
        val requestedMs = to - from
        if (observedSec <= 0.0 || energy.isEmpty()) return AppHeatRanking(emptyList(), coveredMs, requestedMs)
        val totals = energy.mapValues { (_, parts) -> parts.sum() }
        val all = totals.values.sum()
        if (all <= 0.0) return AppHeatRanking(emptyList(), coveredMs, requestedMs)
        val ranked = totals.entries
            .sortedByDescending { it.value }
            .map { (pkg, joules) ->
                AppHeatEntry(
                    packageName = pkg,
                    label = labelOf(pkg),
                    sharePct = (joules / all * 100).toFloat(),
                    avgWatts = (joules / observedSec).toFloat(),
                    sources = sourceLine(energy.getValue(pkg), joules),
                )
            }
            .filter { it.sharePct >= MIN_SHARE }
            .take(MAX_APPS)
        return AppHeatRanking(ranked, coveredMs, requestedMs)
    }

    private fun coveredSeconds(sample: ThermalSampleEntity, from: Long): Double {
        val weightMs = (sample.weightSec * 1000f).toLong()
        if (weightMs <= 0L) return 0.0
        val start = max(from, sample.timestamp - weightMs)
        return (sample.timestamp - start).coerceAtLeast(0L) / 1000.0
    }

    private fun rowsFor(cause: HeatCause, sample: ThermalSampleEntity, at: List<AppUsageEntity>): List<AppUsageEntity> {
        fun res(vararg resources: AppResource) = at.filter { it.resource in resources && it.value > 0f }
        return when (cause) {
            HeatCause.CPU_LOAD -> res(AppResource.CPU).ifEmpty { res(AppResource.FOREGROUND) }
            HeatCause.SCREEN -> res(AppResource.FOREGROUND)
            HeatCause.NETWORK -> when (sample.networkType) {
                NetworkType.CELLULAR -> res(AppResource.MOBILE_DATA)
                NetworkType.WIFI -> res(AppResource.WIFI_DATA)
                else -> res(AppResource.MOBILE_DATA, AppResource.WIFI_DATA)
            }
            HeatCause.GPS -> res(AppResource.GPS)
            HeatCause.CAMERA -> res(AppResource.CAMERA)
            else -> emptyList()
        }
    }

    private fun split(energy: MutableMap<String, DoubleArray>, index: Int, rows: List<AppUsageEntity>, joules: Double) {
        if (rows.isEmpty() || joules <= 0.0) return
        val sum = rows.sumOf { it.value.toDouble() }
        if (sum <= 0.0) return
        rows.groupBy { it.packageName }.forEach { (pkg, group) ->
            val bucket = energy.getOrPut(pkg) { DoubleArray(sources.size) }
            bucket[index] += joules * group.sumOf { it.value.toDouble() } / sum
        }
    }

    private fun sourceLine(parts: DoubleArray, total: Double): String {
        val named = sources.mapIndexed { i, cause -> cause.label to parts[i] }
            .filter { it.second > 0.0 }
            .sortedByDescending { it.second }
        val notable = named.filter { it.second / total >= 0.2 }.ifEmpty { named.take(1) }
        return notable.take(2).joinToString(" · ") { it.first }
    }

    private const val MIN_SHARE = 2f
    private const val MAX_APPS = 8
}

data class AppHeatEntry(
    val packageName: String,
    val label: String,
    val sharePct: Float,
    val avgWatts: Float,
    val sources: String,
)

data class AppHeatRanking(
    val apps: List<AppHeatEntry>,
    val coveredMs: Long,
    val requestedMs: Long,
)
