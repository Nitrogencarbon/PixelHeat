package com.app.pixelheat.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
abstract class ThermalDao {

    @Insert
    abstract suspend fun insertSample(sample: ThermalSampleEntity): Long

    @Insert
    abstract suspend fun insertEvent(event: HardwareEventEntity): Long

    @Query("SELECT * FROM lifetime_stats WHERE id = ${LifetimeStatsEntity.SINGLETON_ID}")
    abstract suspend fun lifetime(): LifetimeStatsEntity?

    @Query("SELECT * FROM lifetime_stats WHERE id = ${LifetimeStatsEntity.SINGLETON_ID}")
    abstract fun observeLifetime(): Flow<LifetimeStatsEntity?>

    @Upsert
    abstract suspend fun upsertLifetime(stats: LifetimeStatsEntity)

    @Query("SELECT * FROM thermal_samples WHERE timestamp >= :from ORDER BY timestamp ASC")
    abstract fun observeSamplesSince(from: Long): Flow<List<ThermalSampleEntity>>

    @Query("SELECT * FROM thermal_samples WHERE timestamp BETWEEN :from AND :to ORDER BY timestamp ASC")
    abstract suspend fun samplesBetween(from: Long, to: Long): List<ThermalSampleEntity>

    @Query("SELECT * FROM hardware_events WHERE timestamp >= :from ORDER BY timestamp ASC")
    abstract fun observeEventsSince(from: Long): Flow<List<HardwareEventEntity>>

    @Query("SELECT * FROM hardware_events WHERE timestamp BETWEEN :from AND :to ORDER BY timestamp ASC")
    abstract suspend fun eventsBetween(from: Long, to: Long): List<HardwareEventEntity>

    @Insert
    abstract suspend fun insertAppUsage(rows: List<AppUsageEntity>)

    @Query("SELECT * FROM app_usage WHERE timestamp >= :from ORDER BY timestamp ASC")
    abstract fun observeAppUsageSince(from: Long): Flow<List<AppUsageEntity>>

    @Query("SELECT * FROM app_usage WHERE timestamp BETWEEN :from AND :to ORDER BY timestamp ASC")
    abstract suspend fun appUsageBetween(from: Long, to: Long): List<AppUsageEntity>

    @Query(
        """
        SELECT (timestamp / 3600000) * 3600000 FROM thermal_samples
        WHERE timestamp < :before
        GROUP BY timestamp / 3600000
        HAVING COUNT(*) > 1
        """,
    )
    abstract suspend fun busySampleHours(before: Long): List<Long>

    @Query(
        """
        SELECT (timestamp / 3600000) * 3600000 FROM app_usage
        WHERE timestamp < :before
        GROUP BY timestamp / 3600000
        HAVING COUNT(DISTINCT timestamp) > 1
        """,
    )
    abstract suspend fun busyUsageHours(before: Long): List<Long>

    @Query(
        """
        SELECT (timestamp / 3600000) * 3600000 FROM hardware_events
        WHERE timestamp < :before
        GROUP BY timestamp / 3600000
        HAVING COUNT(*) > 24
        """,
    )
    abstract suspend fun busyEventHours(before: Long): List<Long>

    @Query(
        """
        SELECT * FROM thermal_samples
        WHERE timestamp >= :start AND timestamp < :end
        ORDER BY timestamp ASC, id ASC
        """,
    )
    abstract suspend fun samplesIn(start: Long, end: Long): List<ThermalSampleEntity>

    @Query(
        """
        SELECT * FROM app_usage
        WHERE timestamp >= :start AND timestamp < :end
        ORDER BY timestamp ASC, id ASC
        """,
    )
    abstract suspend fun usageIn(start: Long, end: Long): List<AppUsageEntity>

    @Query(
        """
        SELECT * FROM hardware_events
        WHERE timestamp >= :start AND timestamp < :end
        ORDER BY timestamp ASC, id ASC
        """,
    )
    abstract suspend fun eventsIn(start: Long, end: Long): List<HardwareEventEntity>

    @Query("DELETE FROM thermal_samples WHERE timestamp >= :start AND timestamp < :end")
    abstract suspend fun deleteSamplesIn(start: Long, end: Long)

    @Query("DELETE FROM app_usage WHERE timestamp >= :start AND timestamp < :end")
    abstract suspend fun deleteUsageIn(start: Long, end: Long)

    @Query("DELETE FROM hardware_events WHERE timestamp >= :start AND timestamp < :end")
    abstract suspend fun deleteEventsIn(start: Long, end: Long)

    /**
     * Replaces the raw rows in [start, end) with one hourly sample, summed app usage and thinned
     * events. Does not touch lifetime totals: those were folded in when each raw sample was stored.
     */
    @Transaction
    open suspend fun compactHour(start: Long, end: Long) {
        if (end <= start) return
        val samples = samplesIn(start, end)
        val usage = usageIn(start, end)
        val events = eventsIn(start, end)
        val rolled = if (samples.size >= 2) HistoryRollup.sample(samples) else null
        val stamp = rolled?.timestamp ?: samples.maxOfOrNull { it.timestamp } ?: (end - 1)
        val rolledUsage = HistoryRollup.usage(usage, samples, stamp)
        val rolledEvents = HistoryRollup.events(events)
        if (rolled == null && rolledUsage == null && rolledEvents == null) return
        if (rolled != null) {
            deleteSamplesIn(start, end)
            insertSample(rolled)
        }
        if (rolledUsage != null) {
            deleteUsageIn(start, end)
            if (rolledUsage.isNotEmpty()) insertAppUsage(rolledUsage)
        }
        if (rolledEvents != null) {
            deleteEventsIn(start, end)
            if (rolledEvents.isNotEmpty()) insertEvents(rolledEvents)
        }
    }

    @Query("SELECT * FROM thermal_samples WHERE id > :afterId ORDER BY id ASC LIMIT :limit")
    abstract suspend fun samplesPage(afterId: Long, limit: Int): List<ThermalSampleEntity>

    @Query("SELECT * FROM hardware_events WHERE id > :afterId ORDER BY id ASC LIMIT :limit")
    abstract suspend fun eventsPage(afterId: Long, limit: Int): List<HardwareEventEntity>

    @Query("SELECT * FROM app_usage WHERE id > :afterId ORDER BY id ASC LIMIT :limit")
    abstract suspend fun appUsagePage(afterId: Long, limit: Int): List<AppUsageEntity>

    @Query("SELECT COUNT(*) FROM thermal_samples")
    abstract suspend fun sampleCount(): Int

    @Insert
    abstract suspend fun insertSamples(samples: List<ThermalSampleEntity>)

    @Insert
    abstract suspend fun insertEvents(events: List<HardwareEventEntity>)

    @Query("DELETE FROM thermal_samples")
    abstract suspend fun deleteAllSamples()

    @Query("DELETE FROM hardware_events")
    abstract suspend fun deleteAllEvents()

    @Query("DELETE FROM app_usage")
    abstract suspend fun deleteAllAppUsage()

    @Query("DELETE FROM lifetime_stats")
    abstract suspend fun deleteLifetime()

    /** Replaces every stored measurement. Sample ids are kept so a restored backup matches the export. */
    @Transaction
    open suspend fun replaceCollected(
        samples: List<ThermalSampleEntity>,
        events: List<HardwareEventEntity>,
        usage: List<AppUsageEntity>,
        lifetime: LifetimeStatsEntity?,
    ) {
        deleteAllSamples()
        deleteAllEvents()
        deleteAllAppUsage()
        deleteLifetime()
        if (samples.isNotEmpty()) insertSamples(samples)
        if (events.isNotEmpty()) insertEvents(events)
        if (usage.isNotEmpty()) insertAppUsage(usage)
        // A backup without totals still has samples: rebuild the totals from them instead of starting at zero.
        val totals = lifetime ?: samples.sortedBy { it.timestamp }.takeIf { it.isNotEmpty() }?.let { sorted ->
            sorted.fold(LifetimeStatsEntity(firstSampleAt = sorted.first().timestamp, lastSampleAt = sorted.first().timestamp)) { stats, s ->
                stats.accumulate(s)
            }
        }
        if (totals != null) upsertLifetime(totals)
    }

    /**
     * Stores a sample and folds it into the lifetime accumulators atomically, so global
     * averages survive pruning of raw samples.
     */
    @Transaction
    open suspend fun insertSampleWithStats(sample: ThermalSampleEntity): ThermalSampleEntity {
        val stats = lifetime() ?: LifetimeStatsEntity(
            firstSampleAt = sample.timestamp,
            lastSampleAt = sample.timestamp,
        )
        val weight = if (stats.sampleCount == 0L) {
            0f
        } else {
            ((sample.timestamp - stats.lastSampleAt) / 1000f).coerceIn(0f, MAX_WEIGHT_SEC)
        }
        val stored = sample.copy(weightSec = weight)
        val id = insertSample(stored)
        upsertLifetime(stats.accumulate(stored))
        return stored.copy(id = id)
    }

    companion object {
        /** Gaps longer than this (service killed, device off) are not counted as observed time. */
        const val MAX_WEIGHT_SEC = 35 * 60f
    }
}
