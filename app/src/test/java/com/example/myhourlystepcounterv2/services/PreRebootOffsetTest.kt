package com.example.myhourlystepcounterv2.services

import com.example.myhourlystepcounterv2.StepTrackerConfig
import com.example.myhourlystepcounterv2.services.StepCounterForegroundService.Companion.computePreRebootInHourSteps
import com.example.myhourlystepcounterv2.services.StepCounterForegroundService.Companion.accumulatePreRebootOffset
import com.example.myhourlystepcounterv2.services.StepCounterForegroundService.Companion.computeDisplayedHourSteps
import com.example.myhourlystepcounterv2.services.StepCounterForegroundService.Companion.computeStepsForBoundarySave
import com.example.myhourlystepcounterv2.services.StepCounterForegroundService.Companion.shouldCheckpointUpdateNotificationWithOffset
import com.example.myhourlystepcounterv2.services.StepCounterForegroundService.Companion.STALE_SENSOR_THRESHOLD_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val MAX = StepTrackerConfig.MAX_STEPS_PER_HOUR

class ComputePreRebootInHourStepsTest {
    @Test
    fun normalCase_returnsDelta() {
        // Pre-reboot the user had baseline 8000, sensor at 8200 → 200 steps in hour
        assertEquals(200, computePreRebootInHourSteps(savedTotal = 8200, savedBaseline = 8000, maxStepsPerHour = MAX))
    }

    @Test
    fun bothZero_returnsZero() {
        assertEquals(0, computePreRebootInHourSteps(savedTotal = 0, savedBaseline = 0, maxStepsPerHour = MAX))
    }

    @Test
    fun corruptedTotalLowerThanBaseline_returnsZero() {
        // Should never happen but defend against it
        assertEquals(0, computePreRebootInHourSteps(savedTotal = 7000, savedBaseline = 8000, maxStepsPerHour = MAX))
    }

    @Test
    fun unreasonableDelta_clampsToMax() {
        assertEquals(MAX, computePreRebootInHourSteps(savedTotal = 999_999, savedBaseline = 0, maxStepsPerHour = MAX))
    }

    @Test
    fun deltaExactlyAtMax_returnsMax() {
        assertEquals(MAX, computePreRebootInHourSteps(savedTotal = MAX, savedBaseline = 0, maxStepsPerHour = MAX))
    }
}

class AccumulatePreRebootOffsetTest {
    @Test
    fun firstReboot_returnsNewInHourSteps() {
        assertEquals(200, accumulatePreRebootOffset(currentOffset = 0, newInHourSteps = 200, maxStepsPerHour = MAX))
    }

    @Test
    fun secondRebootInSameHour_accumulates() {
        // After first reboot offset was 200, user walked 50 post-reboot, then rebooted again
        assertEquals(250, accumulatePreRebootOffset(currentOffset = 200, newInHourSteps = 50, maxStepsPerHour = MAX))
    }

    @Test
    fun zeroNewSteps_preservesCurrent() {
        // Reboot happened with no walking since last save
        assertEquals(200, accumulatePreRebootOffset(currentOffset = 200, newInHourSteps = 0, maxStepsPerHour = MAX))
    }

    @Test
    fun sumWouldExceedMax_clampsToMax() {
        assertEquals(MAX, accumulatePreRebootOffset(currentOffset = MAX - 10, newInHourSteps = 100, maxStepsPerHour = MAX))
    }

    @Test
    fun negativeInputs_clampToZero() {
        // Defensive: should never receive negatives, but if it does
        assertEquals(0, accumulatePreRebootOffset(currentOffset = -50, newInHourSteps = -10, maxStepsPerHour = MAX))
    }
}

class ComputeDisplayedHourStepsTest {
    @Test
    fun noOffset_returnsRawDelta() {
        // Pre-fix behavior: lastKnown=8200, baseline=8000 → 200
        assertEquals(200, computeDisplayedHourSteps(currentTotal = 8200, hourBaseline = 8000, preRebootOffset = 0))
    }

    @Test
    fun postRebootSameHour_addsOffsetToDelta() {
        // After reboot: sensor restarted at 0, user walked 50, offset captures pre-reboot 200
        assertEquals(250, computeDisplayedHourSteps(currentTotal = 50, hourBaseline = 0, preRebootOffset = 200))
    }

    @Test
    fun postRebootBeforeAnyWalking_returnsJustOffset() {
        // Right after reboot: sensor=0, baseline=0, offset=200 → display 200
        assertEquals(200, computeDisplayedHourSteps(currentTotal = 0, hourBaseline = 0, preRebootOffset = 200))
    }

    @Test
    fun negativeDelta_clampedToZeroBeforeAddingOffset() {
        // Defensive: sensor < baseline (shouldn't happen but protect)
        assertEquals(200, computeDisplayedHourSteps(currentTotal = 100, hourBaseline = 500, preRebootOffset = 200))
    }

    @Test
    fun zeroOffset_andZeroDelta_returnsZero() {
        assertEquals(0, computeDisplayedHourSteps(currentTotal = 0, hourBaseline = 0, preRebootOffset = 0))
    }
}

class ComputeStepsForBoundarySaveTest {
    @Test
    fun continuityIntact_returnsDeltaPlusOffset() {
        // Normal hour ending: device went 0 → 500, no pre-reboot offset
        assertEquals(500, computeStepsForBoundarySave(
            deviceTotal = 500, baseline = 0, preRebootOffset = 0,
            continuityBroken = false, maxStepsPerHour = MAX
        ))
    }

    @Test
    fun continuityIntactWithOffset_combinesPreAndPostReboot() {
        // Pre-reboot 200, post-reboot delta 50 → total 250 for the hour
        assertEquals(250, computeStepsForBoundarySave(
            deviceTotal = 50, baseline = 0, preRebootOffset = 200,
            continuityBroken = false, maxStepsPerHour = MAX
        ))
    }

    @Test
    fun continuityBrokenWithOffset_preservesOffsetOnly() {
        // Sensor unreliable but we know pre-reboot in-hour count was 200 — keep that
        assertEquals(200, computeStepsForBoundarySave(
            deviceTotal = 50, baseline = 0, preRebootOffset = 200,
            continuityBroken = true, maxStepsPerHour = MAX
        ))
    }

    @Test
    fun continuityBrokenNoOffset_returnsZero() {
        // Original behaviour preserved
        assertEquals(0, computeStepsForBoundarySave(
            deviceTotal = 50, baseline = 0, preRebootOffset = 0,
            continuityBroken = true, maxStepsPerHour = MAX
        ))
    }

    @Test
    fun negativeDelta_clampedToZero_offsetStillCounted() {
        assertEquals(200, computeStepsForBoundarySave(
            deviceTotal = 50, baseline = 100, preRebootOffset = 200,
            continuityBroken = false, maxStepsPerHour = MAX
        ))
    }

    @Test
    fun sumExceedsMax_clampedToMax() {
        assertEquals(MAX, computeStepsForBoundarySave(
            deviceTotal = MAX, baseline = 0, preRebootOffset = MAX,
            continuityBroken = false, maxStepsPerHour = MAX
        ))
    }
}

class ShouldCheckpointUpdateNotificationWithOffsetTest {
    @Test
    fun staleSensor_estimateWithOffsetExceedsDisplayed_returnsTrue() {
        // Notification stuck at 0; pre-reboot offset 200 + post-reboot delta 50 = 250 > 0
        assertTrue(
            shouldCheckpointUpdateNotificationWithOffset(
                isInitialized = true,
                sensorAgeMs = 60_000L,
                currentTotal = 50,
                hourBaseline = 0,
                preRebootOffset = 200,
                displayedSteps = 0
            )
        )
    }

    @Test
    fun staleSensor_estimateMatchesDisplayed_returnsFalse() {
        // Display already at offset, no new delta
        assertFalse(
            shouldCheckpointUpdateNotificationWithOffset(
                isInitialized = true,
                sensorAgeMs = 60_000L,
                currentTotal = 0,
                hourBaseline = 0,
                preRebootOffset = 200,
                displayedSteps = 200
            )
        )
    }

    @Test
    fun freshSensor_doesNotUpdate() {
        assertFalse(
            shouldCheckpointUpdateNotificationWithOffset(
                isInitialized = true,
                sensorAgeMs = 5_000L,
                currentTotal = 50,
                hourBaseline = 0,
                preRebootOffset = 200,
                displayedSteps = 0
            )
        )
    }

    @Test
    fun notInitialized_doesNotUpdate() {
        assertFalse(
            shouldCheckpointUpdateNotificationWithOffset(
                isInitialized = false,
                sensorAgeMs = 60_000L,
                currentTotal = 50,
                hourBaseline = 0,
                preRebootOffset = 200,
                displayedSteps = 0
            )
        )
    }

    @Test
    fun justAboveStalenessThreshold_updates() {
        assertTrue(
            shouldCheckpointUpdateNotificationWithOffset(
                isInitialized = true,
                sensorAgeMs = STALE_SENSOR_THRESHOLD_MS + 1,
                currentTotal = 0,
                hourBaseline = 0,
                preRebootOffset = 50,
                displayedSteps = 0
            )
        )
    }
}
