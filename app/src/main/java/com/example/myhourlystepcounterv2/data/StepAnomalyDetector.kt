package com.example.myhourlystepcounterv2.data

/**
 * What the device-total snapshot ledger can justify for one hour.
 *
 * [delta] is the step count the raw sensor counter actually moved across the hour.
 * [maxSnapshotGapMs] is the widest interval between consecutive snapshots in that window —
 * the ledger stalls whenever the device deep-sleeps, so a wide gap means the bound
 * under-reports and must not be treated as evidence of anything.
 */
data class CorroboratedBound(
    val delta: Int,
    val maxSnapshotGapMs: Long
)

object StepAnomalyDetector {

    private const val ONE_HOUR_MS = 60L * 60L * 1000L

    /**
     * Bound the steps an hour can contain, or null when the ledger does not bracket the hour
     * on both sides. Without a snapshot at or before the hour start and another at or after
     * the hour end there is nothing to measure against, so the detector stays silent.
     */
    fun corroboratedBound(hourStart: Long, snapshots: List<DeviceTotalSnapshot>): CorroboratedBound? {
        val hourEnd = hourStart + ONE_HOUR_MS
        val ordered = snapshots.sortedBy { it.timestamp }
        val before = ordered.lastOrNull { it.timestamp <= hourStart } ?: return null
        val after = ordered.firstOrNull { it.timestamp >= hourEnd } ?: return null

        // A counter that went backwards was reboot-reset or reseeded by another app. That is
        // not evidence the user took no steps — it is the absence of evidence, so refuse to
        // judge rather than clamping to a zero the hour would then be accused of exceeding.
        if (after.deviceTotal < before.deviceTotal) return null
        val delta = after.deviceTotal - before.deviceTotal

        var maxGap = 0L
        var previous = before.timestamp
        for (snapshot in ordered) {
            if (snapshot.timestamp <= before.timestamp) continue
            if (snapshot.timestamp > after.timestamp) break
            val gap = snapshot.timestamp - previous
            if (gap > maxGap) maxGap = gap
            previous = snapshot.timestamp
        }

        return CorroboratedBound(delta = delta, maxSnapshotGapMs = maxGap)
    }

    /**
     * Steps that may legitimately fall between the last snapshot inside an hour and the hour
     * edge. The ledger samples roughly every 5 minutes, so a brisk walk straddling the
     * boundary can outrun the last sample without anything being wrong.
     */
    const val TOLERANCE_STEPS = 50

    /**
     * Widest snapshot gap that still counts as watching. Beyond this the device was almost
     * certainly dozing, [CorroboratedBound.delta] under-reports, and the bound is not
     * evidence of anything.
     *
     * Raised from 10 to 20 minutes (issue #28). Two independent on-device measurements a day
     * apart (2026-09-09 and 2026-09-10) agree on the shape of the problem: the median snapshot
     * cadence is ~5 minutes (CHECKPOINT_INTERVAL_MINUTES), but ordinary Doze stalls regularly
     * push an hour's widest gap past a 10-minute threshold with no real anomaly present — 8/23
     * bracketed hours judgeable (35%) in the first measurement, 10/23 (43%) in the second; the
     * day-to-day difference is measurement noise, not an effect of any other change, since
     * snapshot cadence is governed by the checkpoint loop's timing, not the sensor-value
     * freshness work in #33/#34/#36. That freshness work is a precondition satisfied, not a
     * measured density improvement: the issue's own sequencing was "trust the values first, then
     * loosen the gap," and this raise is only justified now that step one is verified (see #36's
     * on-device confirmation). 20 minutes recovers most of the coverage lost to the 10-minute
     * threshold (70-78% across both measurements) while the flagship incident this guard exists
     * to catch (StepAnomalyDetectorTest's phantom 03:00 hour, max gap ~395s) stays comfortably
     * inside the bound either way.
     */
    const val MAX_TRUSTED_GAP_MS = 20L * 60L * 1000L

    /**
     * True when [savedSteps] exceeds what the counter provably moved, by more than the
     * sampling tolerance, on a densely sampled hour.
     *
     * Deliberately one-directional: this answers "were these steps invented?", not "are these
     * steps complete". A saved value *below* the bound means steps went missing, which is a
     * different failure with a different cause and is not reported here.
     */
    fun isAnomalous(
        savedSteps: Int,
        bound: CorroboratedBound,
        toleranceSteps: Int = TOLERANCE_STEPS,
        maxTrustedGapMs: Long = MAX_TRUSTED_GAP_MS
    ): Boolean {
        if (bound.maxSnapshotGapMs > maxTrustedGapMs) return false
        return savedSteps > bound.delta + toleranceSteps
    }

    /**
     * Evaluate stored hours that the ledger can now judge.
     *
     * Detection cannot run when an hour is written. The write happens at the boundary, and
     * the ledger has no snapshot past that boundary yet, so the hour cannot be bracketed and
     * every verdict would be "cannot judge" — including for the incident this exists to
     * catch. The evidence only arrives with the next snapshot, so evaluation is deferred to
     * a sweep that runs later and re-reads what is by then a complete window.
     *
     * Skips the hour containing [now]: its row is a partial checkpoint by definition.
     * Skips hours in [alreadyRecorded] so a repeated sweep does not re-report.
     */
    fun sweepCompletedHours(
        storedHours: List<StepEntity>,
        snapshots: List<DeviceTotalSnapshot>,
        sourcePathByHour: Map<Long, String>,
        alreadyRecorded: Set<Long>,
        now: Long,
        detectedAt: Long
    ): List<StepAnomalyEntity> {
        val currentHourStart = now - (now % ONE_HOUR_MS)
        return storedHours.mapNotNull { row ->
            if (row.timestamp in alreadyRecorded) return@mapNotNull null
            if (row.timestamp >= currentHourStart) return@mapNotNull null

            val bound = corroboratedBound(row.timestamp, snapshots) ?: return@mapNotNull null
            if (!isAnomalous(row.stepCount, bound)) return@mapNotNull null

            StepAnomalyEntity(
                hourTimestamp = row.timestamp,
                savedSteps = row.stepCount,
                corroboratedDelta = bound.delta,
                maxSnapshotGapMs = bound.maxSnapshotGapMs,
                sourcePath = sourcePathByHour[row.timestamp] ?: "unknown",
                detectedAt = detectedAt
            )
        }
    }
}
