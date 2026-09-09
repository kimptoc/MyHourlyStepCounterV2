package com.example.myhourlystepcounterv2.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [StepEntity::class, StepAnomalyEntity::class], version = 2, exportSchema = false)
abstract class StepDatabase : RoomDatabase() {
    abstract fun stepDao(): StepDao
    abstract fun stepAnomalyDao(): StepAnomalyDao

    companion object {
        /**
         * Purely additive: creates the anomaly table and does not touch hourly_steps. Room is
         * given this explicitly rather than a destructive fallback, which would discard the
         * step history this app exists to keep.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `step_anomalies` (
                        `hourTimestamp` INTEGER NOT NULL,
                        `savedSteps` INTEGER NOT NULL,
                        `corroboratedDelta` INTEGER NOT NULL,
                        `maxSnapshotGapMs` INTEGER NOT NULL,
                        `sourcePath` TEXT NOT NULL,
                        `detectedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`hourTimestamp`)
                    )
                    """.trimIndent()
                )
            }
        }

        @Volatile
        private var INSTANCE: StepDatabase? = null

        fun getDatabase(context: Context): StepDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    StepDatabase::class.java,
                    "step_database"
                ).addMigrations(MIGRATION_1_2).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
