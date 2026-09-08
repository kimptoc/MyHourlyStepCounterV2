package com.example.myhourlystepcounterv2.services

import com.example.myhourlystepcounterv2.StepTrackerConfig
import com.example.myhourlystepcounterv2.services.StepCounterForegroundService.Companion.BoundaryAction
import com.example.myhourlystepcounterv2.services.StepCounterForegroundService.Companion.resolveBoundaryAction
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
                savedDeviceTotal = 60_000,
                rebootDetected = false
            )
        )
    }

    @Test
    fun shouldDelegateOrdinaryHourTransition_acceptsASavedTotalWhenTheSensorIsStillSilent() {
        // The predicate must not require a delivered sensor event: when only the saved total
        // is real the handler can still close the hour from it.
        //
        // Scope: this pins the predicate alone. It does NOT show a cold start reaching this
        // code — initializeSensorFromPreferences() usually advances currentHourTimestamp
        // first, so the check resolves NONE. See the KDoc's KNOWN GAP note.
        assertTrue(
            shouldDelegateOrdinaryHourTransition(
                hoursDifference = 1L,
                currentDeviceTotal = 0,
                savedDeviceTotal = 60_325,
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
                savedDeviceTotal = 60_000,
                rebootDetected = false
            )
        )
        assertFalse(
            shouldDelegateOrdinaryHourTransition(
                hoursDifference = 7L,
                currentDeviceTotal = 60_325,
                savedDeviceTotal = 60_000,
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
                savedDeviceTotal = 60_000,
                rebootDetected = false
            )
        )
    }

    @Test
    fun shouldDelegateOrdinaryHourTransition_refusesWhenThereIsNoCounterAtAll() {
        // Nothing to close from, and backfill's skip-and-retry is the better behaviour.
        assertFalse(
            shouldDelegateOrdinaryHourTransition(
                hoursDifference = 1L,
                currentDeviceTotal = 0,
                savedDeviceTotal = 0,
                rebootDetected = false
            )
        )
    }

    @Test
    fun shouldDelegateOrdinaryHourTransition_refusesAfterAReboot() {
        // Sensor counter is back at 0 while the saved total is a large pre-reboot value;
        // the handler would set a wildly wrong baseline. Backfill's guards own this case.
        assertFalse(
            shouldDelegateOrdinaryHourTransition(
                hoursDifference = 1L,
                currentDeviceTotal = 60_325,
                savedDeviceTotal = 60_000,
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

    // --- resolveBoundaryAction: the ordering that actually broke ---

    private val hourMs = 60L * 60L * 1000L
    private val currentHour = 1_780_034_400_000L
    private val previousHour = currentHour - hourMs

    private fun action(
        savedHourTimestamp: Long = previousHour,
        effectiveLastProcessed: Long = previousHour,
        currentDeviceTotal: Int = 60_325,
        savedDeviceTotal: Int = 60_000,
        rebootDetected: Boolean = false
    ) = resolveBoundaryAction(
        currentHourTimestamp = currentHour,
        savedHourTimestamp = savedHourTimestamp,
        effectiveLastProcessed = effectiveLastProcessed,
        currentDeviceTotal = currentDeviceTotal,
        savedDeviceTotal = savedDeviceTotal,
        rebootDetected = rebootDetected
    )

    @Test
    fun resolveBoundaryAction_theProductionScenario_handsTheHourToTheBoundaryHandler() {
        // Saved hour is the hour that just completed: the 09:00 -> 10:00 switch that backfill
        // was closing and losing the hour's steps on.
        assertEquals(BoundaryAction.DELEGATE_TO_HANDLER, action())
    }

    @Test
    fun resolveBoundaryAction_handsOffWhenOnlyTheSavedTotalIsReal() {
        // Same scope caveat as the predicate test above: this fixes the decision, not the
        // startup ordering that decides whether the decision is ever reached.
        assertEquals(
            BoundaryAction.DELEGATE_TO_HANDLER,
            action(currentDeviceTotal = 0, savedDeviceTotal = 60_325)
        )
    }

    @Test
    fun resolveBoundaryAction_dedupeWinsOverEverythingElse() {
        // Already processed this hour — no hand-off and no backfill, whatever else is true.
        assertEquals(BoundaryAction.NONE, action(effectiveLastProcessed = currentHour))
    }

    @Test
    fun resolveBoundaryAction_realGapBackfills() {
        assertEquals(
            BoundaryAction.BACKFILL,
            action(savedHourTimestamp = currentHour - 4 * hourMs)
        )
    }

    @Test
    fun resolveBoundaryAction_oneHourGapFallsBackToBackfillWhenTheCounterIsNotTrustworthy() {
        assertEquals(BoundaryAction.BACKFILL, action(rebootDetected = true))
        assertEquals(
            BoundaryAction.BACKFILL,
            action(currentDeviceTotal = 0, savedDeviceTotal = 0)
        )
    }

    @Test
    fun resolveBoundaryAction_noSavedHourIsNothingToClose() {
        assertEquals(BoundaryAction.NONE, action(savedHourTimestamp = 0L))
        assertEquals(BoundaryAction.NONE, action(savedHourTimestamp = -1L))
    }

    @Test
    fun resolveBoundaryAction_savedHourAtOrAheadOfCurrentIsNothingToClose() {
        assertEquals(BoundaryAction.NONE, action(savedHourTimestamp = currentHour))
        assertEquals(BoundaryAction.NONE, action(savedHourTimestamp = currentHour + hourMs))
    }

    @Test
    fun resolveBoundaryAction_subHourGapIsNothingToClose() {
        // Saved timestamp inside the current hour: no boundary has been crossed.
        assertEquals(BoundaryAction.NONE, action(savedHourTimestamp = currentHour - 1_000L))
        assertEquals(BoundaryAction.NONE, action(savedHourTimestamp = currentHour - hourMs + 1))
    }

    @Test
    fun resolveBoundaryAction_backfillAlwaysHasAWholeHourToWrite() {
        // The property the removed `rangeEnd < rangeStart` guard was standing in for:
        // a BACKFILL decision must imply savedHourTimestamp <= currentHour - 1h, so
        // rangeEnd >= rangeStart. Swept across and past the boundary, both counter
        // trustworthiness cases, so it fences the implication rather than one input.
        var saved = currentHour - 5 * hourMs
        while (saved <= currentHour + hourMs) {
            for (reboot in listOf(false, true)) {
                if (action(savedHourTimestamp = saved, rebootDetected = reboot) ==
                    BoundaryAction.BACKFILL
                ) {
                    assertTrue(
                        "BACKFILL with an empty range at saved=$saved (reboot=$reboot)",
                        saved <= currentHour - hourMs
                    )
                }
            }
            saved += 7 * 60_000L
        }
    }
}
