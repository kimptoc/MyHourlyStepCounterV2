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

        val delta = (after.deviceTotal - before.deviceTotal).coerceAtLeast(0)

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
     */
    const val MAX_TRUSTED_GAP_MS = 10L * 60L * 1000L

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
     * Decide whether the value now persisted for an hour needs recording, and as what.
     *
     * [attemptedSteps] is the incoming write; [storedSteps] is what the row already held.
     * Because [StepDao.saveHourlyStepsAtomic] keeps the higher of the two, the value that
     * ends up persisted is their maximum — and when that maximum is the pre-existing row, a
     * correct lower write was just refused, which is the fingerprint of a phantom that can
     * no longer be dislodged. That case is labelled so it is distinguishable from a fresh
     * bad write.
     *
     * Returns null when the hour is corroborated, or when the ledger cannot judge it.
     */
    fun evaluate(
        hourStart: Long,
        attemptedSteps: Int,
        storedSteps: Int?,
        snapshots: List<DeviceTotalSnapshot>,
        sourcePath: String,
        detectedAt: Long
    ): StepAnomalyEntity? {
        val bound = corroboratedBound(hourStart, snapshots) ?: return null

        val existing = storedSteps ?: 0
        val effectiveSteps = maxOf(attemptedSteps, existing)
        if (!isAnomalous(effectiveSteps, bound)) return null

        val rejectedALowerWrite = storedSteps != null && attemptedSteps <= existing
        val label = if (rejectedALowerWrite) "monotonicRejection:$sourcePath" else sourcePath

        return StepAnomalyEntity(
            hourTimestamp = hourStart,
            savedSteps = effectiveSteps,
            corroboratedDelta = bound.delta,
            maxSnapshotGapMs = bound.maxSnapshotGapMs,
            sourcePath = label,
            detectedAt = detectedAt
        )
    }
}
