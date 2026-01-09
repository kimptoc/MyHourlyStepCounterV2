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

    @Query("SELECT * FROM hourly_steps WHERE timestamp >= :startOfDay ORDER BY timestamp DESC")
    fun getStepsForDay(startOfDay: Long): Flow<List<StepEntity>>

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
    suspend fun saveHourlyStepsAtomic(timestamp: Long, stepCount: Int) {
        val existing = getStepForHour(timestamp)
        if (existing == null) {
            // No record yet - insert
            insertStep(StepEntity(timestamp = timestamp, stepCount = stepCount))
        } else if (stepCount > existing.stepCount) {
            // Existing record but new value is higher - update
            insertStep(StepEntity(timestamp = timestamp, stepCount = stepCount))
        } else {
            // Existing record is higher or equal - keep it
            android.util.Log.w(
                "StepDao",
                "Skipping save for hour ${java.util.Date(timestamp)}: existing=${existing.stepCount}, new=$stepCount (keeping existing)"
            )
        }
    }
}
