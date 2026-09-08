package com.example.myhourlystepcounterv2.data

import kotlinx.coroutines.flow.Flow

/**
 * [anomalyDao] and [snapshotProvider] are optional so existing construction sites keep
 * working; where both are supplied, every hourly write is checked against the device-total
 * snapshot ledger and disagreements are recorded. Detection never blocks or alters a step
 * write — a failure in the check is swallowed.
 */
class StepRepository(
    private val stepDao: StepDao,
    private val anomalyDao: StepAnomalyDao? = null,
    private val snapshotProvider: (suspend () -> List<DeviceTotalSnapshot>)? = null
) {
    init {
        // Detection is optional wiring, so its absence is silent by design. Say once, out loud,
        // which mode this repository is in — otherwise "no anomalies logged" is indistinguishable
        // from "the check never ran".
        if (anomalyDao != null && snapshotProvider != null) {
            android.util.Log.i(
                "StepAnomaly",
                "Anomaly detection ARMED (tolerance=${StepAnomalyDetector.TOLERANCE_STEPS} steps, " +
                        "maxTrustedGap=${StepAnomalyDetector.MAX_TRUSTED_GAP_MS}ms)"
            )
        } else {
            // Read-only callers build a repository without the detection dependencies and never
            // reach saveHourlySteps, so this is the normal case rather than a fault. Kept at
            // debug level for that reason: what would matter is a *writing* instance landing
            // here, which shows up as saves with no ARMED line from the same component.
            android.util.Log.d(
                "StepAnomaly",
                "Anomaly detection not wired (read-only repository instance; no hourly writes expected)"
            )
        }
    }

    suspend fun saveHourlySteps(timestamp: Long, stepCount: Int, sourcePath: String = "unknown") {
        // Read before the write so a rejected lower value is still attributable.
        val storedBefore = runCatching { stepDao.getStepForHour(timestamp)?.stepCount }.getOrNull()

        // Use atomic save to prevent race conditions (keeps higher value)
        stepDao.saveHourlyStepsAtomic(timestamp, stepCount)

        recordAnomalyIfAny(timestamp, stepCount, storedBefore, sourcePath)
    }

    private suspend fun recordAnomalyIfAny(
        timestamp: Long,
        stepCount: Int,
        storedBefore: Int?,
        sourcePath: String
    ) {
        val dao = anomalyDao ?: return
        val provider = snapshotProvider ?: return
        try {
            val anomaly = StepAnomalyDetector.evaluate(
                hourStart = timestamp,
                attemptedSteps = stepCount,
                storedSteps = storedBefore,
                snapshots = provider(),
                sourcePath = sourcePath,
                detectedAt = System.currentTimeMillis()
            ) ?: return

            android.util.Log.e(
                "StepAnomaly",
                "Fabricated hour ${java.util.Date(anomaly.hourTimestamp)}: saved=${anomaly.savedSteps} " +
                        "but counter only moved ${anomaly.corroboratedDelta} " +
                        "(maxSnapshotGap=${anomaly.maxSnapshotGapMs}ms, source=${anomaly.sourcePath})"
            )
            dao.insertAnomaly(anomaly)
        } catch (e: Exception) {
            // Observability must never break the write it observes.
            android.util.Log.w("StepAnomaly", "Anomaly check failed for ${java.util.Date(timestamp)}", e)
        }
    }

    fun getRecentAnomalies(limit: Int): Flow<List<StepAnomalyEntity>>? = anomalyDao?.getRecentAnomalies(limit)

    fun getAnomalyCountSince(since: Long): Flow<Int>? = anomalyDao?.getAnomalyCountSince(since)

    suspend fun getStepForHour(timestamp: Long): StepEntity? {
        return stepDao.getStepForHour(timestamp)
    }

    fun getStepCountForHour(timestamp: Long): Flow<Int?> {
        return stepDao.getStepCountForHour(timestamp)
    }

    fun getStepsForDay(startOfDay: Long, currentHourTimestamp: Long): Flow<List<StepEntity>> {
        return stepDao.getStepsForDay(startOfDay, currentHourTimestamp)
    }

    fun getTotalStepsForDay(startOfDay: Long): Flow<Int?> {
        return stepDao.getTotalStepsForDay(startOfDay)
    }

    fun getTotalStepsForDayExcludingCurrentHour(startOfDay: Long, currentHourTimestamp: Long): Flow<Int?> {
        return stepDao.getTotalStepsForDayExcludingCurrentHour(startOfDay, currentHourTimestamp)
    }

    suspend fun deleteOldSteps(cutoffTime: Long) {
        stepDao.deleteOldSteps(cutoffTime)
    }

    suspend fun deleteOldAnomalies(cutoffTime: Long) {
        anomalyDao?.deleteOldAnomalies(cutoffTime)
    }
}
