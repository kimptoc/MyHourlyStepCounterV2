package com.example.myhourlystepcounterv2.ui

import com.example.myhourlystepcounterv2.resolveKnownTotalForInitialization
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StepCounterSyncProbeTest {

    @Test
    fun isSensorReadingFresh_true_whenEventWithinFreshWindow() {
        val now = 10_000L
        val lastEvent = 9_500L

        val result = StepCounterViewModel.isSensorReadingFresh(
            nowMs = now,
            lastEventTimeMs = lastEvent,
            freshEventWindowMs = 1_000L
        )

        assertTrue(result)
    }

    @Test
    fun isSensorReadingFresh_false_whenEventTooOldOrMissing() {
        assertFalse(
            StepCounterViewModel.isSensorReadingFresh(
                nowMs = 10_000L,
                lastEventTimeMs = 8_000L,
                freshEventWindowMs = 1_000L
            )
        )

        assertFalse(
            StepCounterViewModel.isSensorReadingFresh(
                nowMs = 10_000L,
                lastEventTimeMs = 0L,
                freshEventWindowMs = 1_000L
            )
        )
    }

    @Test
    fun shouldStopSyncingFromCallback_true_onlyWhenEventIsAfterProbeStart() {
        assertTrue(
            StepCounterViewModel.shouldStopSyncingFromCallback(
                isSyncing = true,
                syncProbeStartMs = 1_000L,
                lastEventTimeMs = 1_001L
            )
        )

        assertFalse(
            StepCounterViewModel.shouldStopSyncingFromCallback(
                isSyncing = true,
                syncProbeStartMs = 1_000L,
                lastEventTimeMs = 1_000L
            )
        )
    }

    @Test
    fun resolveKnownTotalForInitialization_prefersFreshSensorOverStalePrefs() {
        val knownTotal = resolveKnownTotalForInitialization(
            savedTotal = 437,
            baseline = 13,
            currentDeviceSteps = 13,
            hasFreshSensorEvent = true
        )
        assertTrue(knownTotal == 13)
    }

    @Test
    fun resolveKnownTotalForInitialization_canUseSavedTotal_whenNoFreshSensorEvent() {
        val knownTotal = resolveKnownTotalForInitialization(
            savedTotal = 437,
            baseline = 13,
            currentDeviceSteps = 13,
            hasFreshSensorEvent = false
        )
        assertTrue(knownTotal == 437)
    }
}
