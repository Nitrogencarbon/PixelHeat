package com.app.pixelheat.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.DeleteColumn
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

@Database(
    entities = [ThermalSampleEntity::class, HardwareEventEntity::class, LifetimeStatsEntity::class, AppUsageEntity::class],
    version = 7,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2, spec = PixelHeatDatabase.DropEventDetail::class),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5, spec = PixelHeatDatabase.BackfillPhoneTemperature::class),
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 6, to = 7),
    ],
)
abstract class PixelHeatDatabase : RoomDatabase() {
    abstract fun thermalDao(): ThermalDao

    /** Event text is now generated from type + value, so the stored (localized) sentence is dropped. */
    @DeleteColumn(tableName = "hardware_events", columnName = "detail")
    class DropEventDetail : AutoMigrationSpec

    /**
     * Seeds the phone-score sum from history still on disk. Pruned samples stay in the old battery
     * sum, which is the only temperature those hours still have.
     */
    class BackfillPhoneTemperature : AutoMigrationSpec {
        override fun onPostMigrate(connection: SQLiteConnection) {
            connection.execSQL(
                """
                UPDATE lifetime_stats
                SET referenceTempSum = batteryTempSum + IFNULL((
                    SELECT SUM((IFNULL(skinTemp, batteryTemp) - batteryTemp) * weightSec)
                    FROM thermal_samples
                ), 0)
                """.trimIndent(),
            )
        }
    }

    companion object {
        fun build(context: Context): PixelHeatDatabase =
            Room.databaseBuilder(context, PixelHeatDatabase::class.java, "pixel_heat.db")
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
    }
}
