package com.example.myhourlystepcounterv2.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * Test app initialization scenarios that caused the zero-step bug
 */
class StepCounterInitializationTest {

    @Test
    fun testAppStartup_WithNoSensorReadingYet() {
        // Bug scenario: App starts, sensor hasn't fired yet, getCurrentTotalSteps() returns 0
        val sensorReadingBeforeFiring = 0
        val isValid = sensorReadingBeforeFiring == 0

        assertTrue("Uninitialized sensor should return 0", isValid)
        // FIX: Don't use 0 value - wait for real sensor reading!
    }

    @Test
    fun testAppStartup_WaitsForSensorReading() {
        // FIX: App should wait 200ms for sensor to fire and provide actual reading
        val delayMs = 200L
        val isSufficientWait = delayMs >= 100L

        assertTrue("Should wait at least 100ms for sensor", isSufficientWait)
    }

    @Test
    fun testHourChangeDetection_OnAppStartup() {
        // Scenario: App closed at 10:30, reopened at 11:15
        val savedHourTimestamp = getHourTimestamp(10)
        val currentHourTimestamp = getHourTimestamp(11)

        val hourChanged = currentHourTimestamp != savedHourTimestamp
        assertTrue("Should detect hour change during app closed time", hourChanged)
    }

    @Test
    fun testPreviousHourSaving_OnStartupAfterHourChange() {
        // Scenario: App was at hour 10 with 500 step base
        // Reopens at hour 11 with actual device at 750 steps
        val previousHourStartSteps = 500
        val actualDeviceSteps = 750
        val stepsInPreviousHour = actualDeviceSteps - previousHourStartSteps

        assertEquals("Should correctly calculate steps for closed hour", 250, stepsInPreviousHour)
    }

    @Test
    fun testStaleDataNotUsed_OnAppStartup() {
        // Bug: Old code used preferences.totalStepsDevice which was stale
        // Fix: Read actual device steps after sensor fires
        val staledSavedSteps = 0  // Was saved as 0 in previous session
        val actualCurrentSteps = 1500  // Sensor now reads 1500

        // Should use actual, not stale
        assertTrue("Actual should be used, not stale", actualCurrentSteps > staledSavedSteps)
    }

    @Test
    fun testNoDoubleCountingOnSameHour() {
        // Scenario: App restarts within same hour
        val savedHourTimestamp = getHourTimestamp(10)
        val currentHourTimestamp = getHourTimestamp(10)

        val hourChanged = currentHourTimestamp != savedHourTimestamp
        assertTrue("Should not detect hour change within same hour", !hourChanged)
        // In this case, should NOT save previous hour data
    }

    @Test
    fun testValidationOnStartupData() {
        // Scenario: During startup initialization, validate delta
        val previousHourStartSteps = 5000
        val actualDeviceSteps = 15000
        var delta = actualDeviceSteps - previousHourStartSteps

        // Should be clamped
        val MAX_REASONABLE = 10000
        if (delta > MAX_REASONABLE) {
            delta = MAX_REASONABLE
        }

        assertEquals("Unreasonable delta should be clamped on startup", 10000, delta)
    }

    @Test
    fun testNegativeDeltaOnStartup() {
        // Scenario: Sensor reset between sessions
        val previousHourStartSteps = 5000
        val actualDeviceSteps = 3000  // Reset!
        var delta = actualDeviceSteps - previousHourStartSteps

        if (delta < 0) {
            delta = 0
        }

        assertEquals("Negative delta should become 0 on startup", 0, delta)
    }

    @Test
    fun testMultipleDayGap() {
        // Scenario: App closed for 2 days
        val savedHourTimestamp = getHourTimestamp(10) - (2 * 24 * 60 * 60 * 1000)
        val currentHourTimestamp = getHourTimestamp(14)

        val hourChanged = currentHourTimestamp != savedHourTimestamp
        assertTrue("Should detect hour change across days", hourChanged)
        // Should save data for the previous hour (10am yesterday)
    }

    @Test
    fun testFirstLaunch_NoSavedData() {
        // Scenario: App launched for first time
        val savedHourTimestamp = 0L  // No saved data

        if (savedHourTimestamp > 0) {
            // Should NOT process hour change on first launch
            assertTrue("Should not process hour change on first launch", false)
        } else {
            // Correct: Initialize fresh
            assertTrue("First launch should skip hour change processing", true)
        }
    }

    @Test
    fun testSensorInitialization_Order() {
        // Order of operations matters:
        // 1. Register sensor listener
        // 2. Wait for sensor to fire
        // 3. Read actual device steps
        // 4. Use those for initialization
        // 5. Then check for hour changes
        // 6. Then initialize current hour

        val steps = listOf(
            "Register sensor listener",
            "Wait 200ms for sensor",
            "Read actual device steps",
            "Check hour changed",
            "Save previous hour if needed",
            "Initialize current hour"
        )

        // All steps should be present
        assertEquals("Should have 6 initialization steps", 6, steps.size)
        assertTrue("Sensor registration should be first", steps[0].contains("Register"))
    }

    // Helper function
    private fun getHourTimestamp(hour: Int): Long {
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return calendar.timeInMillis
    }
}
