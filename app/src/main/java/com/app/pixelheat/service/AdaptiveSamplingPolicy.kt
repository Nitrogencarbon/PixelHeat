package com.app.pixelheat.service

import com.app.pixelheat.model.HeatLevel
import com.app.pixelheat.model.ThermalSnapshot
import com.app.pixelheat.model.ThermalStatus

/**
 * Sampling cadence follows Android's thermal status only. A higher status takes effect immediately.
 * A lower one steps down one level at a time, after [DOWNGRADE_VOTES] calmer readings, so the
 * interval does not flap.
 */
class AdaptiveSamplingPolicy {
    private var current = HeatLevel.IDLE
    private var downgradeVotes = 0

    val level: HeatLevel get() = current

    fun evaluate(s: ThermalSnapshot): HeatLevel {
        val target = samplingLevelFor(s.thermalStatus)
        if (target >= current) {
            current = target
            downgradeVotes = 0
        } else if (++downgradeVotes >= DOWNGRADE_VOTES) {
            current = HeatLevel.entries[current.ordinal - 1]
            downgradeVotes = 0
        }
        return current
    }

    companion object {
        private const val DOWNGRADE_VOTES = 2

        fun samplingLevelFor(status: Int): HeatLevel = when {
            status >= ThermalStatus.SEVERE -> HeatLevel.HOT
            status >= ThermalStatus.MODERATE -> HeatLevel.WARM
            status >= ThermalStatus.LIGHT -> HeatLevel.COLD
            else -> HeatLevel.IDLE
        }
    }
}
