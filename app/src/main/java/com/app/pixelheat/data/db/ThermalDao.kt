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

    @Query("DELETE FROM app_usage WHERE timestamp < :before")
    abstract suspend fun pruneAppUsage(before: Long): Int

    @Query("DELETE FROM thermal_samples WHERE timestamp < :before")
    abstract suspend fun pruneSamples(before: Long): Int

    @Query("DELETE FROM hardware_events WHERE timestamp < :before")
    abstract suspend fun pruneEvents(before: Long): Int

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
