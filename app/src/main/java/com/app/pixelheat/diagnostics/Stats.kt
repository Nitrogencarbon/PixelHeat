package com.app.pixelheat.diagnostics

import com.app.pixelheat.data.db.ThermalSampleEntity
import com.app.pixelheat.data.db.referenceTemp
import kotlin.math.min
import kotlin.math.sqrt

object Stats {

    fun pearson(x: List<Double>, y: List<Double>): Double? {
        val n = min(x.size, y.size)
        if (n < 3) return null
        val mx = x.take(n).average()
        val my = y.take(n).average()
        var cov = 0.0
        var vx = 0.0
        var vy = 0.0
        for (i in 0 until n) {
            val dx = x[i] - mx
            val dy = y[i] - my
            cov += dx * dy
            vx += dx * dx
            vy += dy * dy
        }
        if (vx < 1e-9 || vy < 1e-9) return null
        return cov / sqrt(vx * vy)
    }

    /** Least-squares slope of y over x. */
    fun slope(x: List<Double>, y: List<Double>): Double? {
        val n = min(x.size, y.size)
        if (n < 2) return null
        val mx = x.take(n).average()
        val my = y.take(n).average()
        var num = 0.0
        var den = 0.0
        for (i in 0 until n) {
            num += (x[i] - mx) * (y[i] - my)
            den += (x[i] - mx) * (x[i] - mx)
        }
        return if (den < 1e-9) null else num / den
    }

    fun weightedMean(values: List<Double>, weights: List<Double>): Double {
        var sum = 0.0
        var w = 0.0
        for (i in values.indices) {
            sum += values[i] * weights[i]
            w += weights[i]
        }
        return if (w > 0) sum / w else 0.0
    }

    /** Linear interpolation of the reference temperature at [t]; null outside the sampled range. */
    fun referenceAt(samples: List<ThermalSampleEntity>, t: Long): Double? {
        if (samples.isEmpty() || t < samples.first().timestamp || t > samples.last().timestamp) return null
        // Samples are sorted by time: the first one at or after t, found by binary search.
        var lo = 0
        var hi = samples.lastIndex
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (samples[mid].timestamp < t) lo = mid + 1 else hi = mid
        }
        val idx = lo
        val b = samples[idx]
        if (b.timestamp == t || idx == 0) return b.referenceTemp.toDouble()
        val a = samples[idx - 1]
        val f = (t - a.timestamp).toDouble() / (b.timestamp - a.timestamp)
        return a.referenceTemp + (b.referenceTemp - a.referenceTemp) * f
    }
}
