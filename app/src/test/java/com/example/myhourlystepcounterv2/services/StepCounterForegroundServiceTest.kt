package com.example.myhourlystepcounterv2.services

import com.example.myhourlystepcounterv2.services.StepCounterForegroundService.Companion.SensorAction
import com.example.myhourlystepcounterv2.services.StepCounterForegroundService.Companion.FLUSH_THRESHOLD_MS
import com.example.myhourlystepcounterv2.services.StepCounterForegroundService.Companion.RE_REGISTER_THRESHOLD_MS
import com.example.myhourlystepcounterv2.services.StepCounterForegroundService.Companion.DORMANT_THRESHOLD_MS
import com.example.myhourlystepcounterv2.services.StepCounterForegroundService.Companion.determineSensorAction
import com.example.myhourlystepcounterv2.services.StepCounterForegroundService.Companion.isDeviceRebootDetected
import com.example.myhourlystepcounterv2.services.StepCounterForegroundService.Companion.shouldBreakCounterContinuity
import com.example.myhourlystepcounterv2.services.StepCounterForegroundService.Companion.shouldClearNotificationSyncState
import com.example.myhourlystepcounterv2.services.StepCounterForegroundService.Companion.shouldCheckpointUpdateNotification
import com.example.myhourlystepcounterv2.services.StepCounterForegroundService.Companion.STALE_SENSOR_THRESHOLD_MS
import com.example.myhourlystepcounterv2.resolveKnownTotalForInitialization
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StepCounterForegroundServiceTest {
    @Test
    fun resolvePreviousHourTimestamp_correctsStaleTimestamp() {
        val currentHour = 1_700_000_000_000L
        val stale = currentHour - (2 * 60 * 60 * 1000)
        val expected = currentHour - (60 * 60 * 1000)

        val resolved = StepCounterForegroundService.resolvePreviousHourTimestamp(
            currentHourTimestamp = currentHour,
            savedHourTimestamp = stale
        )

        assertEquals(expected, resolved)
    }

    @Test
    fun isDeviceRebootDetected_trueWhenBootCountChanges() {
        assertTrue(isDeviceRebootDetected(currentBootCount = 28, savedBootCount = 27))
    }

    @Test
    fun isDeviceRebootDetected_falseWhenSavedBootCountUnknown() {
        assertEquals(false, isDeviceRebootDetected(currentBootCount = 28, savedBootCount = -1))
    }

    @Test
    fun isDeviceRebootDetected_falseWhenCurrentBootCountUnavailable() {
        assertEquals(false, isDeviceRebootDetected(currentBootCount = -1, savedBootCount = 28))
    }

    @Test
    fun shouldBreakCounterContinuity_trueWhenCounterDropsWithoutRebootFlag() {
        assertTrue(
            shouldBreakCounterContinuity(
                currentDeviceTotal = 200,
                savedDeviceTotal = 1000,
                rebootDetected = false
            )
        )
    }

    @Test
    fun shouldBreakCounterContinuity_falseWhenCounterMonotonicAndNoReboot() {
        assertEquals(
            false,
            shouldBreakCounterContinuity(
                currentDeviceTotal = 1500,
                savedDeviceTotal = 1000,
                rebootDetected = false
            )
        )
    }

    @Test
    fun shouldBreakCounterContinuity_trueWhenRebootDetectedEvenIfMonotonic() {
        assertEquals(
            true,
            shouldBreakCounterContinuity(
                currentDeviceTotal = 1500,
                savedDeviceTotal = 1000,
                rebootDetected = true
            )
        )
    }
}

class SensorStalenessTest {

    // Use a non-zero lastEventTime for tests that aren't testing cold start
    private val someEventTime = 1_700_000_000_000L

    @Test
    fun freshSensor_returnsNone() {
        // 30 seconds old — well within flush threshold
        assertEquals(SensorAction.NONE, determineSensorAction(30_000L, DORMANT_THRESHOLD_MS, someEventTime))
    }

    @Test
    fun justBelowFlushThreshold_returnsNone() {
        assertEquals(SensorAction.NONE, determineSensorAction(FLUSH_THRESHOLD_MS - 1, DORMANT_THRESHOLD_MS, someEventTime))
    }

    @Test
    fun atFlushThreshold_returnsNone() {
        // Threshold check is > not >=, so exactly at threshold returns NONE
        assertEquals(SensorAction.NONE, determineSensorAction(FLUSH_THRESHOLD_MS, DORMANT_THRESHOLD_MS, someEventTime))
    }

    @Test
    fun aboveFlushThreshold_belowReRegister_returnsFlush() {
        // 2 minutes old, dormant threshold is 10 min
        assertEquals(SensorAction.FLUSH, determineSensorAction(2 * 60_000L, DORMANT_THRESHOLD_MS, someEventTime))
    }

    @Test
    fun aboveDormantThreshold_returnsReRegister() {
        // 11 minutes old
        assertEquals(SensorAction.RE_REGISTER, determineSensorAction(11 * 60_000L, DORMANT_THRESHOLD_MS, someEventTime))
    }

    @Test
    fun zeroSensorAge_returnsNone() {
        assertEquals(SensorAction.NONE, determineSensorAction(0L, DORMANT_THRESHOLD_MS, someEventTime))
    }

    @Test
    fun postBoundary_belowReRegisterThreshold_returnsFlushOrNone() {
        // Using RE_REGISTER_THRESHOLD_MS (5 min) as the upper threshold
        // 3 minutes — above flush, below re-register
        assertEquals(SensorAction.FLUSH, determineSensorAction(3 * 60_000L, RE_REGISTER_THRESHOLD_MS, someEventTime))
    }

    @Test
    fun postBoundary_aboveReRegisterThreshold_returnsReRegister() {
        // 6 minutes with 5-min threshold
        assertEquals(SensorAction.RE_REGISTER, determineSensorAction(6 * 60_000L, RE_REGISTER_THRESHOLD_MS, someEventTime))
    }

    @Test
    fun thresholdConstants_areInExpectedOrder() {
        // Flush < Re-register < Dormant
        assertTrue(FLUSH_THRESHOLD_MS < RE_REGISTER_THRESHOLD_MS)
        assertTrue(RE_REGISTER_THRESHOLD_MS < DORMANT_THRESHOLD_MS)
    }

    @Test
    fun coldStart_noEventYet_returnsNone() {
        // lastEventTimeMs=0 means sensor hasn't fired yet — don't re-register
        assertEquals(SensorAction.NONE, determineSensorAction(Long.MAX_VALUE, DORMANT_THRESHOLD_MS, 0L))
    }

    @Test
    fun coldStart_noEventYet_ignoresAllThresholds() {
        // Even with huge age and small threshold, 0 lastEventTime means NONE
        assertEquals(SensorAction.NONE, determineSensorAction(999_999_999L, FLUSH_THRESHOLD_MS, 0L))
    }
}

class NotificationSyncStateTest {
    @Test
    fun shouldClearNotificationSyncState_true_whenSyncingAndFreshEventSeen() {
        assertTrue(
            shouldClearNotificationSyncState(
                currentSyncing = true,
                lastSensorEventTimeMs = 123L
            )
        )
    }

    @Test
    fun shouldClearNotificationSyncState_false_whenAlreadyNotSyncing() {
        assertEquals(
            false,
            shouldClearNotificationSyncState(
                currentSyncing = false,
                lastSensorEventTimeMs = 123L
            )
        )
    }

    @Test
    fun shouldClearNotificationSyncState_false_whenNoSensorEventYet() {
        assertEquals(
            false,
            shouldClearNotificationSyncState(
                currentSyncing = true,
                lastSensorEventTimeMs = 0L
            )
        )
    }
}

class InitializationSeedResolutionTest {
    @Test
    fun resolveKnownTotalForInitialization_prefersFreshSensorReading_whenAvailable() {
        val knownTotal = resolveKnownTotalForInitialization(
            savedTotal = 437,
            baseline = 13,
            currentDeviceSteps = 13,
            hasFreshSensorEvent = true
        )
        assertEquals(13, knownTotal)
    }

    @Test
    fun resolveKnownTotalForInitialization_usesSavedWhenSensorNotFresh() {
        val knownTotal = resolveKnownTotalForInitialization(
            savedTotal = 437,
            baseline = 13,
            currentDeviceSteps = 13,
            hasFreshSensorEvent = false
        )
        assertEquals(437, knownTotal)
    }
}

class CheckpointNotificationUpdateTest {

    @Test
    fun shouldUpdate_whenSensorStaleAndEstimateExceedsDisplayed() {
        assertTrue(
            shouldCheckpointUpdateNotification(
                isInitialized = true,
                sensorAgeMs = 60_000L,          // 1 minute, well above 30s threshold
                currentTotal = 5100,
                hourBaseline = 5000,
                displayedSteps = 0              // notification stuck at 0
            )
        )
    }

    @Test
    fun shouldNotUpdate_whenSensorIsFresh() {
        // Sensor event arrived 10s ago — onSensorChanged is delivering fine
        assertFalse(
            shouldCheckpointUpdateNotification(
                isInitialized = true,
                sensorAgeMs = 10_000L,
                currentTotal = 5100,
                hourBaseline = 5000,
                displayedSteps = 0
            )
        )
    }

    @Test
    fun shouldNotUpdate_whenNotInitialized() {
        assertFalse(
            shouldCheckpointUpdateNotification(
                isInitialized = false,
                sensorAgeMs = 60_000L,
                currentTotal = 5100,
                hourBaseline = 5000,
                displayedSteps = 0
            )
        )
    }

    @Test
    fun shouldNotUpdate_whenDisplayAlreadyHigherThanEstimate() {
        // Notification already shows 200, estimate is only 100
        assertFalse(
            shouldCheckpointUpdateNotification(
                isInitialized = true,
                sensorAgeMs = 60_000L,
                currentTotal = 5100,
                hourBaseline = 5000,
                displayedSteps = 200
            )
        )
    }

    @Test
    fun shouldNotUpdate_whenEstimateEqualsDisplayed() {
        // Must be strictly greater to trigger update
        assertFalse(
            shouldCheckpointUpdateNotification(
                isInitialized = true,
                sensorAgeMs = 60_000L,
                currentTotal = 5100,
                hourBaseline = 5000,
                displayedSteps = 100
            )
        )
    }

    @Test
    fun shouldNotUpdate_whenEstimateIsNegative() {
        // hourBaseline > currentTotal (e.g., sensor reset)
        assertFalse(
            shouldCheckpointUpdateNotification(
                isInitialized = true,
                sensorAgeMs = 60_000L,
                currentTotal = 4900,
                hourBaseline = 5000,
                displayedSteps = 0
            )
        )
    }

    @Test
    fun shouldUpdate_atExactStalenessThresholdPlusOne() {
        // Just above the 30s threshold
        assertTrue(
            shouldCheckpointUpdateNotification(
                isInitialized = true,
                sensorAgeMs = STALE_SENSOR_THRESHOLD_MS + 1,
                currentTotal = 5050,
                hourBaseline = 5000,
                displayedSteps = 0
            )
        )
    }

    @Test
    fun shouldNotUpdate_atExactStalenessThreshold() {
        // Exactly at the threshold — condition is > not >=
        assertFalse(
            shouldCheckpointUpdateNotification(
                isInitialized = true,
                sensorAgeMs = STALE_SENSOR_THRESHOLD_MS,
                currentTotal = 5050,
                hourBaseline = 5000,
                displayedSteps = 0
            )
        )
    }
}
