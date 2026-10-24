package com.app.pixelheat.diagnostics

import com.app.pixelheat.data.db.AppUsageEntity
import com.app.pixelheat.data.db.HardwareEventEntity
import com.app.pixelheat.model.AppResource
import com.app.pixelheat.model.HardwareEventType
import com.app.pixelheat.sensors.AppActivityReader
import com.app.pixelheat.data.db.ThermalSampleEntity
import com.app.pixelheat.data.db.referenceTemp
import com.app.pixelheat.model.NetworkType
import com.app.pixelheat.model.PlugType
import com.app.pixelheat.model.ThermalStatus
import com.app.pixelheat.util.fmt
import com.app.pixelheat.util.formatPower
import com.app.pixelheat.util.formatRate
import com.app.pixelheat.util.formatSignedTemp
import com.app.pixelheat.util.formatTemp
import com.app.pixelheat.util.formatTime
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Explains *why* the phone is warming up.
 *
 * For each candidate cause it combines two signals:
 *  1. an energy prior from [HeatModel] (how many watts of heat the cause injects), and
 *  2. temporal evidence: Pearson correlation between the cause's intensity and the
 *     following temperature derivative (dT/dt, one sample of lag).
 * Score = mean watts × (0.55 + 0.45 × max(0, r)). Scores are normalised into shares.
 */
class CauseEffectAnalyzer(private val labelOf: (String) -> String = ::defaultLabel) {

    fun analyze(
        samples: List<ThermalSampleEntity>,
        events: List<HardwareEventEntity>,
        apps: List<AppUsageEntity> = emptyList(),
    ): DiagnosisReport {
        val s = samples.sortedBy { it.timestamp }
        val last = s.lastOrNull() ?: return DiagnosisReport.insufficient(null)
        val spanMin = (last.timestamp - s.first().timestamp) / 60_000.0
        if (s.size < MIN_SAMPLES || spanMin < MIN_SPAN_MIN) return DiagnosisReport.insufficient(last.referenceTemp)

        val t0 = s.first().timestamp
        val minutes = s.map { (it.timestamp - t0) / 60_000.0 }
        val ref = s.map { it.referenceTemp.toDouble() }

        val recentFrom = last.timestamp - RECENT_WINDOW_MS
        val recent = s.indices.filter { s[it].timestamp >= recentFrom }.let { if (it.size >= 2) it else s.indices.toList().takeLast(2) }
        val rate = Stats.slope(recent.map { minutes[it] }, recent.map { ref[it] }) ?: 0.0
        val rise = ref.last() - ref.min()
        val status = classify(last, rate)

        val dTdt = (1 until s.size).map { i -> (ref[i] - ref[i - 1]) / max(minutes[i] - minutes[i - 1], 0.25) }
        val weights = s.map { if (it.timestamp >= recentFrom) 2.0 else 1.0 }

        val scored = HeatCause.entries.mapNotNull { cause ->
            val series = s.map { HeatModel.watts(cause, it) }
            val mean = Stats.weightedMean(series, weights)
            if (mean < MIN_CAUSE_W) return@mapNotNull null
            val lagged = (1 until s.size).map { i -> (series[i - 1] + series[i]) / 2 }
            val r = Stats.pearson(lagged, dTdt)
            val score = mean * (0.55 + 0.45 * max(0.0, r ?: 0.0))
            val deltaActive = (1 until s.size).filter { series[it - 1] > ACTIVE_W }.sumOf { ref[it] - ref[it - 1] }
            val activePct = series.count { it > ACTIVE_W } * 100f / series.size
            Scored(cause, score, mean, r, activePct, deltaActive)
        }
        val total = scored.sumOf { it.score }.takeIf { it > 0 } ?: 1.0
        val windowApps = apps.filter { it.timestamp in s.first().timestamp..last.timestamp }
        val heat = heatWeights(s)
        val causes = scored.sortedByDescending { it.score }.map {
            CauseContribution(
                cause = it.cause,
                label = causeLabel(it.cause, s),
                sharePct = (it.score / total * 100).toFloat(),
                avgWatts = it.mean.toFloat(),
                correlation = it.r?.toFloat(),
                activePct = it.activePct,
                deltaWhileActiveC = it.deltaActive.toFloat(),
                apps = attribute(it.cause, s, heat, windowApps),
            )
        }

        val impacts = events
            .filter { it.active && it.timestamp in s.first().timestamp..last.timestamp }
            .mapNotNull { eventImpact(s, it) }
            .sortedByDescending { it.deltaC }

        val throttling = last.thermalStatus >= ThermalStatus.MODERATE
        val primary = causes.firstOrNull()
        return DiagnosisReport(
            status = status,
            referenceTemp = last.referenceTemp,
            rateCPerMin = rate.toFloat(),
            riseC = rise.toFloat(),
            windowMin = spanMin.toFloat(),
            causes = causes,
            headline = headline(status, primary),
            explanation = explanation(status, s, causes, rate, rise, spanMin, throttling, impacts),
            advice = causes.filter { it.sharePct >= ADVICE_MIN_SHARE }.take(3).map { it.cause.advice },
            eventImpacts = impacts,
            throttling = throttling,
            generatedAt = System.currentTimeMillis(),
        )
    }

    /** Temperature change in the [IMPACT_WINDOW_MS] after an event compared to the trend before it. */
    fun eventImpact(sorted: List<ThermalSampleEntity>, event: HardwareEventEntity): EventImpact? {
        if (sorted.isEmpty()) return null
        val t = event.timestamp
        val atEvent = Stats.referenceAt(sorted, t) ?: return null
        val afterT = min(t + IMPACT_WINDOW_MS, sorted.last().timestamp)
        if (afterT - t < MIN_IMPACT_MS) return null
        val after = Stats.referenceAt(sorted, afterT) ?: return null
        val minutesAfter = (afterT - t) / 60_000.0
        val rateAfter = (after - atEvent) / minutesAfter
        val before = Stats.referenceAt(sorted, t - IMPACT_WINDOW_MS)
        val rateBefore = before?.let { (atEvent - it) / (IMPACT_WINDOW_MS / 60_000.0) } ?: 0.0
        return EventImpact(
            event = event,
            deltaC = (after - atEvent).toFloat(),
            minutes = minutesAfter.toFloat(),
            rateChange = (rateAfter - rateBefore).toFloat(),
        )
    }

    /**
     * How much each sample belongs to a heat peak: the rise across it (the interval it covers and the
     * one that follows, since heat lags the load) plus how far it sits in the hot part of the window.
     * Zero when the window never warmed up.
     */
    private fun heatWeights(s: List<ThermalSampleEntity>): Map<Long, Double> {
        val ref = s.map { it.referenceTemp.toDouble() }
        val floor = ref.min()
        val range = ref.max() - floor
        val hotFrom = when {
            range >= MIN_PEAK_RANGE_C -> floor + PEAK_FRACTION * range
            ref.max() >= EPISODE_TEMP_C -> EPISODE_TEMP_C.toDouble()
            else -> Double.MAX_VALUE
        }
        return s.indices.associate { i ->
            val rise = max(0.0, ref[min(i + 1, s.lastIndex)] - ref[max(i - 1, 0)])
            val hot = max(0.0, ref[i] - hotFrom)
            s[i].timestamp to rise + HOT_WEIGHT * hot
        }
    }

    /**
     * Which apps were running *while the phone heated up*, not which ran longest. Each sample's per-app
     * share of the resource is weighted by that sample's [heatWeights]; apps that are present at the peaks
     * but much less at other times get a boost (lift), so an always-on app does not win just by being there.
     * CPU uses per-process share when a privileged shell recorded it, otherwise the foreground app.
     */
    private fun attribute(
        cause: HeatCause,
        s: List<ThermalSampleEntity>,
        heat: Map<Long, Double>,
        apps: List<AppUsageEntity>,
    ): List<AppShare> {
        if (apps.isEmpty()) return emptyList()
        fun of(vararg resources: AppResource) = apps.filter { it.resource in resources }
        val rows = when (cause) {
            HeatCause.CPU_LOAD -> of(AppResource.CPU).ifEmpty { of(AppResource.FOREGROUND) }
            HeatCause.SCREEN -> of(AppResource.FOREGROUND)
            HeatCause.NETWORK -> of(AppResource.MOBILE_DATA, AppResource.WIFI_DATA)
            HeatCause.GPS -> of(AppResource.GPS)
            HeatCause.CAMERA -> of(AppResource.CAMERA).ifEmpty {
                val cameraAt = s.filter { it.cameraActive }.map { it.timestamp }.toSet()
                of(AppResource.FOREGROUND).filter { it.timestamp in cameraAt }
            }
            HeatCause.CHARGING, HeatCause.SUNLIGHT, HeatCause.AMBIENT -> emptyList()
        }
        return rankByHeat(rows, heat)
    }

    private fun rankByHeat(rows: List<AppUsageEntity>, heat: Map<Long, Double>): List<AppShare> {
        val byTime = rows.filter { it.value > 0 && it.timestamp in heat }.groupBy { it.timestamp }
        val totalHeat = byTime.keys.sumOf { heat.getValue(it) }
        if (byTime.isEmpty() || totalHeat < MIN_HEAT) return emptyList()

        val heatScore = HashMap<String, Double>()
        val presence = HashMap<String, Double>()
        val meanShare = HashMap<String, Double>()
        for ((t, atT) in byTime) {
            val h = heat.getValue(t)
            val sum = atT.sumOf { it.value.toDouble() }
            atT.groupBy { it.packageName }.forEach { (pkg, r) ->
                val share = r.sumOf { it.value.toDouble() } / sum
                heatScore.merge(pkg, h * share, Double::plus)
                presence.merge(pkg, h, Double::plus)
                meanShare.merge(pkg, share / byTime.size, Double::plus)
            }
        }
        val scores = heatScore.mapValues { (pkg, score) ->
            val heatShare = score / totalHeat
            val lift = heatShare / meanShare.getValue(pkg).coerceAtLeast(1e-6)
            heatShare * kotlin.math.sqrt(lift.coerceIn(MIN_LIFT, MAX_LIFT))
        }
        val sum = scores.values.sum().takeIf { it > 0 } ?: return emptyList()
        return scores.entries.sortedByDescending { it.value }
            .map {
                AppShare(
                    packageName = it.key,
                    label = labelOf(it.key),
                    sharePct = (it.value / sum * 100).toFloat(),
                    peakPct = (presence.getValue(it.key) / totalHeat * 100).toFloat().coerceAtMost(100f),
                )
            }
            .filter { it.sharePct >= MIN_APP_SHARE }
            .take(MAX_APPS)
    }

    /** Apps across every resource, ranked by how much they coincide with the heat in [s]. */
    private fun suspects(s: List<ThermalSampleEntity>, apps: List<AppUsageEntity>): List<AppShare> {
        if (s.size < 2 || apps.isEmpty()) return emptyList()
        val heat = heatWeights(s)
        val cpu = apps.filter { it.resource == AppResource.CPU }
        val foreground = apps.filter { it.resource == AppResource.FOREGROUND }
        val hardware = apps.filter { it.resource == AppResource.CAMERA || it.resource == AppResource.GPS }
        val ranked = (rankByHeat(cpu.ifEmpty { foreground }, heat) + rankByHeat(hardware, heat))
        return ranked.groupBy { it.packageName }
            .map { (_, shares) -> shares.maxBy { it.peakPct * it.sharePct } }
            .sortedByDescending { it.peakPct * it.sharePct }
            .take(MAX_APPS)
    }

    /** Overheating episodes in history, newest first, each with its diagnosed cause. */
    fun findEpisodes(
        samples: List<ThermalSampleEntity>,
        events: List<HardwareEventEntity>,
        apps: List<AppUsageEntity> = emptyList(),
    ): List<HeatEpisode> {
        val s = samples.sortedBy { it.timestamp }
        val segments = mutableListOf<LongRange>()
        var start: Long? = null
        var lastHot = 0L
        for (sample in s) {
            val hot = sample.referenceTemp >= EPISODE_TEMP_C || sample.thermalStatus >= ThermalStatus.MODERATE
            if (!hot) continue
            val open = start
            if (open == null) {
                start = sample.timestamp
            } else if (sample.timestamp - lastHot > EPISODE_MERGE_GAP_MS) {
                segments += open..lastHot
                start = sample.timestamp
            }
            lastHot = sample.timestamp
        }
        start?.let { segments += it..lastHot }

        return segments.reversed().take(MAX_EPISODES).map { range ->
            val inside = s.filter { it.timestamp in range }
            val peak = inside.maxBy { it.referenceTemp }
            val context = s.filter { it.timestamp in (range.first - EPISODE_CONTEXT_MS)..range.last }
            val ctxRange = (range.first - EPISODE_CONTEXT_MS)..range.last
            val ctxApps = apps.filter { it.timestamp in ctxRange }
            val ctxEvents = events.filter { it.timestamp in ctxRange }
            val report = analyze(context, ctxEvents, ctxApps)
            val cause = report.primary
            val suspects = cause?.apps?.ifEmpty { null } ?: suspects(context, ctxApps)
            HeatEpisode(
                start = range.first,
                end = range.last,
                peakTemp = peak.referenceTemp,
                peakZone = peak.hottestZone?.let { "$it ${formatTemp(peak.maxTemp)}" },
                primaryCause = cause,
                summary = cause?.let { c -> "${c.label} (${c.sharePct.roundToInt()}%)" } ?: "Cause undetermined",
                suspects = suspects,
                hardwareUsers = ctxEvents
                    .filter { it.active && it.appName != null && it.type in OWNED_EVENT_TYPES }
                    .map { "${it.type.label}: ${it.appName}" }
                    .distinct(),
            )
        }
    }

    private fun classify(last: ThermalSampleEntity, rate: Double): DiagnosisStatus {
        val t = last.referenceTemp
        return when {
            t >= CRITICAL_C || last.thermalStatus >= ThermalStatus.SEVERE -> DiagnosisStatus.CRITICAL
            t >= HOT_C || last.thermalStatus >= ThermalStatus.MODERATE -> DiagnosisStatus.HOT
            rate >= HEATING_RATE -> DiagnosisStatus.HEATING
            rate <= -HEATING_RATE -> DiagnosisStatus.COOLING
            else -> DiagnosisStatus.NORMAL
        }
    }

    private fun causeLabel(cause: HeatCause, s: List<ThermalSampleEntity>): String = when (cause) {
        HeatCause.CHARGING -> when {
            s.any { it.isCharging && it.plugType == PlugType.WIRELESS } -> "Wireless charging"
            s.any { it.fastCharging } -> "Fast charging"
            else -> "Charging"
        }
        HeatCause.NETWORK -> if (s.count { it.networkType == NetworkType.CELLULAR } > s.size / 2) "Mobile data / modem" else "Wi-Fi"
        else -> cause.label
    }

    private fun headline(status: DiagnosisStatus, primary: CauseContribution?): String = when (status) {
        DiagnosisStatus.INSUFFICIENT_DATA -> "Collecting data"
        DiagnosisStatus.NORMAL -> "Temperature is normal"
        DiagnosisStatus.COOLING -> "The phone is cooling down"
        DiagnosisStatus.HEATING, DiagnosisStatus.HOT, DiagnosisStatus.CRITICAL -> primary?.let { c ->
            val app = c.apps.firstOrNull()?.takeIf { it.sharePct >= HEADLINE_APP_SHARE && it.peakPct >= HEADLINE_APP_PEAK }
            "Overheating caused by ${c.label.lowercase()}" + (app?.let { " from ${it.label}" }.orEmpty())
        } ?: "Overheating of unclear origin"
    }

    private fun explanation(
        status: DiagnosisStatus,
        s: List<ThermalSampleEntity>,
        causes: List<CauseContribution>,
        rate: Double,
        rise: Double,
        spanMin: Double,
        throttling: Boolean,
        impacts: List<EventImpact>,
    ): String = buildString {
        val last = s.last()
        val primary = causes.firstOrNull()
        when (status) {
            DiagnosisStatus.NORMAL -> {
                append("Phone temperature ${formatTemp(last.referenceTemp)}, stable (${signedRate(rate)}).")
                primary?.let { append(" The main heat source right now is ${it.label.lowercase()} (${it.sharePct.roundToInt()}%).") }
                return@buildString
            }
            DiagnosisStatus.COOLING -> {
                append("Temperature is dropping (${signedRate(rate)}) and is now ${formatTemp(last.referenceTemp)}.")
                return@buildString
            }
            else -> append(
                "Temperature is rising (${signedRate(rate)}): ${formatSignedTemp(rise.toFloat())} " +
                    "over the last ${spanMin.roundToInt()} min, now ${formatTemp(last.referenceTemp)}. ",
            )
        }
        primary?.let { append(describe(it, s)) }
        primary?.apps?.takeIf { it.isNotEmpty() }?.let { append(" ${appsSentence(primary.cause, it)}") }
        primary?.correlation?.let { r ->
            when {
                r >= 0.5f -> append(" Its correlation with the temperature rise is strong (r = ${r.fmt(2)}).")
                r >= 0.25f -> append(" Its correlation with the temperature rise is moderate (r = ${r.fmt(2)}).")
            }
        }
        val secondary = causes.drop(1).filter { it.sharePct >= 10f }.take(2)
        if (secondary.isNotEmpty()) {
            append(" Also contributing: ")
            append(
                secondary.joinToString(", ") { c ->
                    "${c.label.lowercase()} (${c.sharePct.roundToInt()}%" +
                        (c.apps.firstOrNull()?.let { ", mostly ${it.label}" }.orEmpty()) + ")"
                },
            )
            append(".")
        }
        impacts.firstOrNull { it.significant }?.let {
            append(
                " Most impactful event: ${it.event.type.label.lowercase()} at ${formatTime(it.event.timestamp)}, " +
                    "followed by ${formatSignedTemp(it.deltaC)} in ${it.minutes.roundToInt()} min.",
            )
        }
        if (throttling) {
            append(" Android is throttling performance (status: ${ThermalStatus.label(last.thermalStatus).lowercase()}).")
        }
    }

    private fun describe(c: CauseContribution, s: List<ThermalSampleEntity>): String {
        val active = "${c.activePct.roundToInt()}% of the time"
        val delta = if (abs(c.deltaWhileActiveC) >= 0.3f) ", during which temperature changed by ${formatSignedTemp(c.deltaWhileActiveC)}" else ""
        return when (c.cause) {
            HeatCause.CHARGING -> {
                val charging = s.filter { it.isCharging }
                val inW = charging.map { it.powerW }.filter { it > 0 }.average().takeIf { !it.isNaN() }?.toFloat()
                val maxW = charging.mapNotNull { it.chargerMaxW }.maxOrNull()
                "${c.label} was active $active$delta: " +
                    (inW?.let { "${formatPower(it)} input" } ?: "input power not measurable") +
                    (maxW?.let { " (charger up to ${formatPower(it)})" }.orEmpty()) +
                    ", with about ${formatPower(c.avgWatts)} lost as heat in the battery."
            }
            HeatCause.CPU_LOAD -> {
                val drain = s.filter { !it.isCharging && it.powerW < 0 }.map { -it.powerW }.average().takeIf { !it.isNaN() }?.toFloat()
                val cpu = s.mapNotNull { it.cpuTemp }.maxOrNull()
                val freq = s.mapNotNull { it.cpuFreqPct }.average().takeIf { !it.isNaN() }?.toFloat()
                "Running apps are stressing the processor" +
                    (drain?.let { ": average drain ${formatPower(it)}" }.orEmpty()) +
                    (freq?.let { ", average frequency ${it.roundToInt()}%" }.orEmpty()) +
                    (cpu?.let { ", CPU peak ${formatTemp(it)}" }.orEmpty()) + "."
            }
            HeatCause.SCREEN -> {
                val b = s.filter { it.screenOn }.mapNotNull { it.brightnessPct }.average().takeIf { !it.isNaN() }?.roundToInt()
                "The display was on $active" + (b?.let { " at $it% average brightness" }.orEmpty()) +
                    "$delta, contributing about ${formatPower(c.avgWatts)}."
            }
            HeatCause.NETWORK -> {
                val peak = s.maxOf { it.rxKBps + it.txKBps }
                "Data traffic over ${c.label.lowercase()} peaked at ${formatRate(peak)}$delta: the modem is a major heat source, especially on weak signal or 5G."
            }
            HeatCause.GPS -> "GPS stayed on $active$delta, plus the location processing done by apps."
            HeatCause.CAMERA -> "The camera was in use $active$delta: sensor, ISP and video encoding generate a lot of heat."
            HeatCause.SUNLIGHT -> {
                val lux = s.mapNotNull { it.lux }.maxOrNull()?.roundToInt() ?: 0
                "The phone is in direct sunlight (up to $lux lux) and is absorbing external heat$delta."
            }
            HeatCause.AMBIENT -> {
                val amb = s.mapNotNull { it.ambientTemp }.average().toFloat()
                val src = s.last().ambientSource.label.lowercase()
                "An estimated outside temperature of ${formatTemp(amb)} ($src) limits how well the phone can shed heat."
            }
        }
    }

    private fun appsSentence(cause: HeatCause, apps: List<AppShare>): String {
        val list = apps.joinToString(", ") { "${it.label} (active for ${it.peakPct.roundToInt()}% of the heating)" }
        return when (cause) {
            HeatCause.CPU_LOAD -> "While it heated up, the processor was busy with $list."
            HeatCause.SCREEN -> "While it heated up, on screen: $list."
            HeatCause.NETWORK -> "While it heated up, data was used by $list."
            HeatCause.GPS -> "While it heated up, GPS was requested by $list."
            HeatCause.CAMERA -> "While it heated up, the camera was used by $list."
            else -> "Apps active while it heated up: $list."
        }
    }

    private fun signedRate(rate: Double) = (if (rate >= 0) "+" else "") + "${rate.fmt(2)} °C/min"

    private data class Scored(
        val cause: HeatCause,
        val score: Double,
        val mean: Double,
        val r: Double?,
        val activePct: Float,
        val deltaActive: Double,
    )

    companion object {
        const val HOT_C = 40f
        const val CRITICAL_C = 44f
        const val EPISODE_TEMP_C = 40f
        private const val HEATING_RATE = 0.15
        private const val MIN_SAMPLES = 3
        private const val MIN_SPAN_MIN = 2.0
        private const val MIN_CAUSE_W = 0.05
        private const val ACTIVE_W = 0.1
        private const val ADVICE_MIN_SHARE = 15f
        private const val RECENT_WINDOW_MS = 15 * 60_000L
        private const val IMPACT_WINDOW_MS = 10 * 60_000L
        private const val MIN_IMPACT_MS = 2 * 60_000L
        private const val EPISODE_MERGE_GAP_MS = 10 * 60_000L
        private const val EPISODE_CONTEXT_MS = 20 * 60_000L
        private const val MAX_EPISODES = 8
        private const val MIN_APP_SHARE = 8f
        private const val MAX_APPS = 3
        private const val HEADLINE_APP_SHARE = 40f
        private const val HEADLINE_APP_PEAK = 60f
        private const val MIN_PEAK_RANGE_C = 0.5
        private const val PEAK_FRACTION = 0.6
        private const val HOT_WEIGHT = 0.5
        private const val MIN_HEAT = 0.05
        private const val MIN_LIFT = 0.25
        private const val MAX_LIFT = 4.0
        private val OWNED_EVENT_TYPES = setOf(HardwareEventType.CAMERA, HardwareEventType.GPS)

        fun defaultLabel(pkg: String): String = AppActivityReader.nativeLabel(pkg) ?: pkg
    }
}
