package com.example.myhourlystepcounterv2.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface StepDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStep(step: StepEntity)

    @Query("SELECT * FROM hourly_steps WHERE timestamp = :timestamp")
    suspend fun getStepForHour(timestamp: Long): StepEntity?

    @Query("SELECT stepCount FROM hourly_steps WHERE timestamp = :timestamp")
    fun getStepCountForHour(timestamp: Long): Flow<Int?>

    @Query("SELECT * FROM hourly_steps WHERE timestamp >= :startOfDay AND timestamp < :currentHourTimestamp ORDER BY timestamp DESC")
    fun getStepsForDay(startOfDay: Long, currentHourTimestamp: Long): Flow<List<StepEntity>>

    @Query("SELECT * FROM hourly_steps WHERE timestamp >= :start AND timestamp <= :end ORDER BY timestamp")
    suspend fun getStepsInRange(start: Long, end: Long): List<StepEntity>

    @Query("DELETE FROM hourly_steps WHERE timestamp < :cutoffTime")
    suspend fun deleteOldSteps(cutoffTime: Long)

    @Query("SELECT SUM(stepCount) FROM hourly_steps WHERE timestamp >= :startOfDay")
    fun getTotalStepsForDay(startOfDay: Long): Flow<Int?>

    @Query("SELECT SUM(stepCount) FROM hourly_steps WHERE timestamp >= :startOfDay AND timestamp != :currentHourTimestamp")
    fun getTotalStepsForDayExcludingCurrentHour(startOfDay: Long, currentHourTimestamp: Long): Flow<Int?>

    /**
     * Atomically save hourly steps with conflict prevention.
     * If a record already exists, only update if new value is higher.
     * This prevents WorkManager from overwriting ViewModel's closure distribution.
     */
    @Transaction
    suspend fun saveHourlyStepsAtomic(timestamp: Long, stepCount: Int): Boolean {
        val existing = getStepForHour(timestamp)
        // Branch on the policy itself rather than re-deriving its conditions here: the return
        // value decides who may claim authorship of the hour, so a second copy of the rule
        // drifting out of step would misattribute silently.
        val persisted = StepWritePolicy.persists(existing?.stepCount, stepCount)
        if (persisted) {
            insertStep(StepEntity(timestamp = timestamp, stepCount = stepCount))
            if (existing == null) {
                android.util.Log.i(
                    "StepDao",
                    "Inserted hour ${java.util.Date(timestamp)}: steps=$stepCount"
                )
            } else {
                android.util.Log.i(
                    "StepDao",
                    "Updated hour ${java.util.Date(timestamp)}: existing=${existing.stepCount}, new=$stepCount"
                )
            }
        } else {
            // Existing record is higher or equal - keep it
            android.util.Log.w(
                "StepDao",
                "Skipping save for hour ${java.util.Date(timestamp)}: existing=${existing?.stepCount}, new=$stepCount (keeping existing)"
            )
        }
        return persisted
    }
}
