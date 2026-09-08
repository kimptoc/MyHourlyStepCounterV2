package com.example.myhourlystepcounterv2.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface StepAnomalyDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAnomaly(anomaly: StepAnomalyEntity)

    @Query("SELECT * FROM step_anomalies ORDER BY hourTimestamp DESC LIMIT :limit")
    fun getRecentAnomalies(limit: Int): Flow<List<StepAnomalyEntity>>

    @Query("SELECT COUNT(*) FROM step_anomalies WHERE detectedAt >= :since")
    fun getAnomalyCountSince(since: Long): Flow<Int>

    @Query("DELETE FROM step_anomalies WHERE hourTimestamp < :cutoffTime")
    suspend fun deleteOldAnomalies(cutoffTime: Long)
}
