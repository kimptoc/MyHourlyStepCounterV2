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
    private val snapshotProvider: (suspend () -> List<DeviceTotalSnapshot>)? = null,
    private val sourcePathRecorder: (suspend (Long, String) -> Unit)? = null,
    private val sourcePathReader: (suspend () -> Map<Long, String>)? = null
) {
    companion object {
        /** How far back a sweep looks. Matches the snapshot ledger's own retention. */
        const val SWEEP_WINDOW_MS = 24L * 60L * 60L * 1000L
    }

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
        // Use atomic save to prevent race conditions (keeps higher value)
        val persisted = stepDao.saveHourlyStepsAtomic(timestamp, stepCount)

        // Only remember who wrote this hour, and only if the write actually landed — the
        // atomic save drops anything not higher than the stored row, and a dropped writer
        // stamping its name here would blame it for a value it never wrote. The hour itself
        // cannot be judged yet: the ledger has no snapshot past the boundary we are standing
        // on, so sweepForAnomalies() does the judging once the evidence exists.
        if (!persisted) return
        try {
            sourcePathRecorder?.invoke(timestamp, sourcePath)
        } catch (e: Exception) {
            android.util.Log.w("StepAnomaly", "Could not record writer for ${java.util.Date(timestamp)}", e)
        }
    }

    /**
     * Evaluate completed hours the snapshot ledger can now bracket, and record any that claim
     * more steps than the counter moved. Safe to call repeatedly — hours already recorded are
     * skipped. Never alters step data.
     *
     * Because this reads stored rows rather than incoming writes, it also catches a phantom
     * that a later correct (lower) write could not dislodge: saveHourlyStepsAtomic only ever
     * raises a value, so such a row would otherwise stay wrong and unreported forever.
     */
    suspend fun sweepForAnomalies(now: Long = System.currentTimeMillis()) {
        val dao = anomalyDao ?: return
        val provider = snapshotProvider ?: return
        try {
            val windowStart = now - SWEEP_WINDOW_MS
            val stored = stepDao.getStepsInRange(windowStart, now)
            if (stored.isEmpty()) return

            val found = StepAnomalyDetector.sweepCompletedHours(
                storedHours = stored,
                snapshots = provider(),
                sourcePathByHour = sourcePathReader?.invoke() ?: emptyMap(),
                alreadyRecorded = dao.getAnomalyHoursSince(windowStart).toSet(),
                now = now,
                detectedAt = now
            )

            for (anomaly in found) {
                android.util.Log.e(
                    "StepAnomaly",
                    "Fabricated hour ${java.util.Date(anomaly.hourTimestamp)}: saved=${anomaly.savedSteps} " +
                            "but counter only moved ${anomaly.corroboratedDelta} " +
                            "(maxSnapshotGap=${anomaly.maxSnapshotGapMs}ms, source=${anomaly.sourcePath})"
                )
                dao.insertAnomaly(anomaly)
            }
        } catch (e: Exception) {
            // Observability must never break the app that hosts it.
            android.util.Log.w("StepAnomaly", "Anomaly sweep failed", e)
        }
    }

    fun getRecentAnomalies(limit: Int): Flow<List<StepAnomalyEntity>>? = anomalyDao?.getRecentAnomalies(limit)

    /**
     * Coverage for the Profile screen's "N of M hours verified" display (issue #28 step 3).
     * `total` is completed hours with a stored row in the window, not every hour the window
     * spans -- an hour with no row at all (never written, or pruned) isn't counted either way,
     * so this answers "of what we have, how much is verified", not "of what elapsed".
     * Null when this repository has no snapshot ledger wired up (read-only instances) rather
     * than a misleading 0-of-0, so the UI can tell "not wired" apart from "wired, nothing yet".
     */
    suspend fun getAnomalyCoverage(now: Long = System.currentTimeMillis()): StepAnomalyDetector.CoverageResult? {
        val provider = snapshotProvider ?: return null
        return try {
            val windowStart = now - SWEEP_WINDOW_MS
            val stored = stepDao.getStepsInRange(windowStart, now)
            StepAnomalyDetector.countCoverage(stored, provider(), now)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("StepAnomaly", "Coverage computation failed", e)
            null
        }
    }

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

}
