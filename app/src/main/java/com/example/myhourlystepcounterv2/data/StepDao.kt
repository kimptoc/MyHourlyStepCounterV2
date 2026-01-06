package com.example.myhourlystepcounterv2.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
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
}
