package com.example.myhourlystepcounterv2.services

import com.example.myhourlystepcounterv2.StepTrackerConfig
import com.example.myhourlystepcounterv2.services.StepCounterForegroundService.Companion.shouldDelegateOrdinaryHourTransition
import com.example.myhourlystepcounterv2.services.StepCounterForegroundService.Companion.reconcileBoundarySaveWithDisplay
import com.example.myhourlystepcounterv2.services.StepCounterForegroundService.Companion.resolveBackfillHourSteps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression cover for the hourly switch closing an hour on the wrong value, which showed up
 * as a "goal achieved" notification for an hour the timeline then marked as a miss.
 */
class HourlySwitchReconciliationTest {

    @Test
    fun shouldDelegateOrdinaryHourTransition_handsOffTheSingleHourGap() {
        assertTrue(
            shouldDelegateOrdinaryHourTransition(
                hoursDifference = 1L,
                currentDeviceTotal = 60_325,
                rebootDetected = false
            )
        )
    }

    @Test
    fun shouldDelegateOrdinaryHourTransition_rejectsRealMissedBoundaryGaps() {
        assertFalse(
            shouldDelegateOrdinaryHourTransition(
                hoursDifference = 2L,
                currentDeviceTotal = 60_325,
                rebootDetected = false
            )
        )
        assertFalse(
            shouldDelegateOrdinaryHourTransition(
                hoursDifference = 7L,
                currentDeviceTotal = 60_325,
                rebootDetected = false
            )
        )
    }

    @Test
    fun shouldDelegateOrdinaryHourTransition_rejectsNoGap() {
        assertFalse(
            shouldDelegateOrdinaryHourTransition(
                hoursDifference = 0L,
                currentDeviceTotal = 60_325,
                rebootDetected = false
            )
        )
    }

    @Test
    fun shouldDelegateOrdinaryHourTransition_refusesWhenSensorHasNotReportedYet() {
        // Backfill's post-reboot guards must stay in charge when there is no live counter.
        assertFalse(
            shouldDelegateOrdinaryHourTransition(
                hoursDifference = 1L,
                currentDeviceTotal = 0,
                rebootDetected = false
            )
        )
    }

    @Test
    fun shouldDelegateOrdinaryHourTransition_refusesAfterAReboot() {
        assertFalse(
            shouldDelegateOrdinaryHourTransition(
                hoursDifference = 1L,
                currentDeviceTotal = 60_325,
                rebootDetected = true
            )
        )
    }

    @Test
    fun reconcileBoundarySaveWithDisplay_keepsDisplayedCountWhenItLeadsTheRecomputedTotal() {
        // The 09:58 achievement showed 325; a boundary recompute of 0 would mark the hour a miss.
        val saved = reconcileBoundarySaveWithDisplay(
            computedSteps = 0,
            displayedSteps = 325,
            continuityBroken = false,
            maxStepsPerHour = StepTrackerConfig.MAX_STEPS_PER_HOUR
        )

        assertEquals(325, saved)
    }

    @Test
    fun reconcileBoundarySaveWithDisplay_keepsComputedTotalWhenItLeadsTheDisplay() {
        val saved = reconcileBoundarySaveWithDisplay(
            computedSteps = 900,
            displayedSteps = 325,
            continuityBroken = false,
            maxStepsPerHour = StepTrackerConfig.MAX_STEPS_PER_HOUR
        )

        assertEquals(900, saved)
    }

    @Test
    fun reconcileBoundarySaveWithDisplay_ignoresDisplayWhenCounterContinuityIsBroken() {
        // Post-reboot the displayed value is not evidence about the completed hour.
        val saved = reconcileBoundarySaveWithDisplay(
            computedSteps = 40,
            displayedSteps = 9000,
            continuityBroken = true,
            maxStepsPerHour = StepTrackerConfig.MAX_STEPS_PER_HOUR
        )

        assertEquals(40, saved)
    }

    @Test
    fun reconcileBoundarySaveWithDisplay_clampsToMaxAndFloorsAtZero() {
        assertEquals(
            StepTrackerConfig.MAX_STEPS_PER_HOUR,
            reconcileBoundarySaveWithDisplay(
                computedSteps = 0,
                displayedSteps = 999_999,
                continuityBroken = false,
                maxStepsPerHour = StepTrackerConfig.MAX_STEPS_PER_HOUR
            )
        )
        assertEquals(
            0,
            reconcileBoundarySaveWithDisplay(
                computedSteps = -5,
                displayedSteps = -20,
                continuityBroken = false,
                maxStepsPerHour = StepTrackerConfig.MAX_STEPS_PER_HOUR
            )
        )
    }

    @Test
    fun resolveBackfillHourSteps_raisesPartialCheckpointToMeasuredDelta() {
        val steps = resolveBackfillHourSteps(
            existingSteps = 0,          // checkpoint written early in the hour
            snapshotTotal = 60_325,
            previousTotal = 60_000,
            maxStepsPerHour = StepTrackerConfig.MAX_STEPS_PER_HOUR
        )

        assertEquals(325, steps)
    }

    @Test
    fun resolveBackfillHourSteps_leavesStoredRowAloneWhenItAlreadyLeads() {
        val steps = resolveBackfillHourSteps(
            existingSteps = 400,
            snapshotTotal = 60_325,
            previousTotal = 60_000,
            maxStepsPerHour = StepTrackerConfig.MAX_STEPS_PER_HOUR
        )

        assertNull(steps)
    }

    @Test
    fun resolveBackfillHourSteps_writesMeasuredDeltaForAnHourWithNoRow() {
        val steps = resolveBackfillHourSteps(
            existingSteps = null,
            snapshotTotal = 60_500,
            previousTotal = 60_000,
            maxStepsPerHour = StepTrackerConfig.MAX_STEPS_PER_HOUR
        )

        assertEquals(500, steps)
    }

    @Test
    fun resolveBackfillHourSteps_returnsNullWithoutASnapshotToMeasureAgainst() {
        assertNull(
            resolveBackfillHourSteps(
                existingSteps = 0,
                snapshotTotal = null,
                previousTotal = 60_000,
                maxStepsPerHour = StepTrackerConfig.MAX_STEPS_PER_HOUR
            )
        )
    }

    @Test
    fun resolveBackfillHourSteps_returnsNullWhenSnapshotRegressesBelowReference() {
        assertNull(
            resolveBackfillHourSteps(
                existingSteps = null,
                snapshotTotal = 59_000,
                previousTotal = 60_000,
                maxStepsPerHour = StepTrackerConfig.MAX_STEPS_PER_HOUR
            )
        )
    }

    @Test
    fun resolveBackfillHourSteps_clampsMeasuredDeltaToMaxStepsPerHour() {
        val steps = resolveBackfillHourSteps(
            existingSteps = null,
            snapshotTotal = 200_000,
            previousTotal = 60_000,
            maxStepsPerHour = StepTrackerConfig.MAX_STEPS_PER_HOUR
        )

        assertEquals(StepTrackerConfig.MAX_STEPS_PER_HOUR, steps)
    }
}
