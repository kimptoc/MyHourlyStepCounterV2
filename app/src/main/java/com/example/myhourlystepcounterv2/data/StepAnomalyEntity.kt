package com.example.myhourlystepcounterv2.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One recorded disagreement between a persisted hourly step count and what the device-total
 * snapshot ledger could justify for that hour.
 *
 * Purely observational. Recording an anomaly never alters the step data it describes — the
 * ledger under-reports whenever the device dozes, so it is trustworthy as a detector of
 * invented steps but never as an authority on the true count.
 */
@Entity(tableName = "step_anomalies")
data class StepAnomalyEntity(
    /** Start of the offending hour, matching [StepEntity.timestamp]. */
    @PrimaryKey val hourTimestamp: Long,
    /** The value that is (or would be) persisted for that hour. */
    val savedSteps: Int,
    /** Steps the raw sensor counter provably moved across the hour. */
    val corroboratedDelta: Int,
    /** Widest snapshot gap in the window — how closely the ledger was watching. */
    val maxSnapshotGapMs: Long,
    /** Which code path produced the value, so the mechanism can be traced later. */
    val sourcePath: String,
    val detectedAt: Long
)
