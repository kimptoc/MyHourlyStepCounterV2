package com.example.myhourlystepcounterv2.services

import com.example.myhourlystepcounterv2.data.DeviceTotalSnapshot
import com.example.myhourlystepcounterv2.services.StepCounterForegroundService.Companion.resolveBackfillReferenceTotal
import com.example.myhourlystepcounterv2.services.StepCounterForegroundService.Companion.isBackfillReferencePlausible
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackfillBaselineTest {

    private val hour = 60 * 60 * 1000L

    @Test
    fun resolveBackfillReferenceTotal_usesSnapshotAtOrBeforeRangeStart() {
        val rangeStart = 1_780_023_600_000L // 04:00
        val snapshots = listOf(
            DeviceTotalSnapshot(rangeStart - 3 * 60 * 1000, 59266), // 03:57
            DeviceTotalSnapshot(rangeStart + 57 * 60 * 1000, 59266) // inside the window
        )

        val reference = resolveBackfillReferenceTotal(
            savedDeviceTotal = 0,
            snapshots = snapshots,
            rangeStart = rangeStart
        )

        assertEquals(59266, reference)
    }

    @Test
    fun resolveBackfillReferenceTotal_picksLatestSnapshotAtOrBeforeRangeStart() {
        val rangeStart = 1_780_023_600_000L
        val snapshots = listOf(
            DeviceTotalSnapshot(rangeStart - 30 * 60 * 1000, 59000),
            DeviceTotalSnapshot(rangeStart - 5 * 60 * 1000, 59266),
            DeviceTotalSnapshot(rangeStart + 60 * 1000, 60000)
        )

        val reference = resolveBackfillReferenceTotal(
            savedDeviceTotal = 12345,
            snapshots = snapshots,
            rangeStart = rangeStart
        )

        assertEquals(59266, reference)
    }

    @Test
    fun resolveBackfillReferenceTotal_fallsBackToSavedTotalWhenNoSnapshotBeforeRange() {
        val rangeStart = 1_780_023_600_000L
        val snapshots = listOf(
            DeviceTotalSnapshot(rangeStart + 60 * 1000, 60000) // only after range start
        )

        val reference = resolveBackfillReferenceTotal(
            savedDeviceTotal = 59225,
            snapshots = snapshots,
            rangeStart = rangeStart
        )

        assertEquals(59225, reference)
    }

    @Test
    fun resolveBackfillReferenceTotal_recoversTrueReferenceWhenSavedTotalIsZero() {
        // Regression for issue #16: savedDeviceTotal read as 0 must NOT cause the
        // entire lifetime count to be treated as steps while closed. The snapshot
        // entering the missed window is the true reference.
        val rangeStart = 1_780_023_600_000L
        val snapshots = listOf(DeviceTotalSnapshot(rangeStart - 2 * 60 * 1000, 59266))

        val reference = resolveBackfillReferenceTotal(
            savedDeviceTotal = 0,
            snapshots = snapshots,
            rangeStart = rangeStart
        )

        assertEquals(59266, reference)
    }

    @Test
    fun isBackfillReferencePlausible_falseWhenReferenceZero() {
        assertFalse(
            isBackfillReferencePlausible(
                referenceTotal = 0,
                deviceTotalToUse = 59266,
                missedHourCount = 1,
                maxStepsPerHour = 10000
            )
        )
    }

    @Test
    fun isBackfillReferencePlausible_falseWhenDeltaExceedsPhysicalMaxAcrossMissedHours() {
        // 59266 - 0-ish over a single missed hour is physically impossible (> 10000).
        assertFalse(
            isBackfillReferencePlausible(
                referenceTotal = 49000,
                deviceTotalToUse = 59266,
                missedHourCount = 1,
                maxStepsPerHour = 10000
            )
        )
    }

    @Test
    fun isBackfillReferencePlausible_trueForNormalClosure() {
        assertTrue(
            isBackfillReferencePlausible(
                referenceTotal = 59000,
                deviceTotalToUse = 59266,
                missedHourCount = 2,
                maxStepsPerHour = 10000
            )
        )
    }

    @Test
    fun isBackfillReferencePlausible_allowsDeltaUpToMaxTimesMissedHours() {
        assertTrue(
            isBackfillReferencePlausible(
                referenceTotal = 50000,
                deviceTotalToUse = 70000, // 20000 across 2 hours == 2 * 10000
                missedHourCount = 2,
                maxStepsPerHour = 10000
            )
        )
    }
}
