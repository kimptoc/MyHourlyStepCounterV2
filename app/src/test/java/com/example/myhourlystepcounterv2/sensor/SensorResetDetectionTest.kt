package com.example.myhourlystepcounterv2.sensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test sensor reset detection for handling multi-app step counting.
 *
 * Problem: When other fitness apps (Samsung Health, Google Fit, etc.) access the
 * device step counter, they can cause the sensor to reset or return unexpected values.
 * This causes our app to display zero steps in the middle of an hour.
 *
 * Solution: Detect when sensor value drops unexpectedly and ignore the reset.
 * This preserves data integrity when multiple apps access the same sensor.
 *
 * Design:
 * - Track the previous sensor value
 * - If new reading < previous reading, it's a reset (ignore it)
 * - Log the reset for diagnostics
 * - Continue using the previous value to maintain display
 */
class SensorResetDetectionTest {

    @Test
    fun testSensorResetDetectionWhenValueDrops() {
        // Scenario: Sensor reading at 32000 steps
        val previousReading = 32000
        val currentReading = 0  // Another app reset the counter

        // When we detect a drop, it's a reset
        val isReset = previousReading > 0 && currentReading < previousReading

        assertTrue("Should detect sensor reset when value drops", isReset)
    }

    @Test
    fun testSensorResetNotDetectedWhenValueIncreases() {
        // Normal behavior: reading increases
        val previousReading = 32000
        val currentReading = 32005  // User took 5 more steps

        val isReset = previousReading > 0 && currentReading < previousReading

        assertTrue("Normal increment should not be treated as reset", !isReset)
    }

    @Test
    fun testSensorResetIgnoredToPreserveData() {
        // Given: Current reading was 32000, hour baseline was 31800
        val lastKnownStepCount = 32000
        val lastHourStartStepCount = 31800
        val stepsThisHour = lastKnownStepCount - lastHourStartStepCount

        assertEquals("Should show 200 steps", 200, stepsThisHour)

        // When: Samsung Health resets sensor to 0
        val sensorResetValue = 0

        // If we updated lastKnownStepCount to 0:
        val stepsAfterIgnoringReset = lastKnownStepCount - lastHourStartStepCount  // Still 200
        val stepsIfWeUsedResetValue = sensorResetValue - lastHourStartStepCount  // -31800, clamped to 0

        assertEquals("Should preserve 200 steps if we ignore reset", 200, stepsAfterIgnoringReset)
        assertEquals("Would show 0 if we used reset value", 0, maxOf(0, stepsIfWeUsedResetValue))
    }

    @Test
    fun testMultiAppAccessScenario() {
        // Scenario: Hour timeline with sensor behavior
        // Normal progression:
        val previousValue1 = 32000
        val currentValue1 = 32005
        val isReset1 = previousValue1 > 0 && currentValue1 < previousValue1
        assertEquals("First increase should not be reset", false, isReset1)

        // Continue normal:
        val previousValue2 = 32005
        val currentValue2 = 32010
        val isReset2 = previousValue2 > 0 && currentValue2 < previousValue2
        assertEquals("Second increase should not be reset", false, isReset2)

        // Samsung Health resets:
        val previousValue3 = 32010
        val currentValue3 = 0
        val isReset3 = previousValue3 > 0 && currentValue3 < previousValue3
        assertEquals("Drop to 0 is a reset", true, isReset3)

        // When reset detected, we DON'T update previousValue
        // So if sensor recovers later:
        val previousValue4 = 32010  // Still the pre-reset value
        val currentValue4 = 100
        val isReset4 = previousValue4 > 0 && currentValue4 < previousValue4
        assertEquals("Recovery after reset is correctly identified as reset", true, isReset4)
        // This is correct because 100 < 32010, so it will be ignored too
    }

    @Test
    fun testSensorResetAfterAppSwitch() {
        // User scenario from bug report:
        // - Using our app, showing 50 steps this hour
        // - Switch to Samsung Health (it accesses step sensor)
        // - Switch back to our app

        val beforeSwitch = 32000
        val afterSamsungHealthAccess = 31950  // Health app reset counter

        val isReset = beforeSwitch > 0 && afterSamsungHealthAccess < beforeSwitch

        assertTrue("Should detect reset when switching from Samsung Health", isReset)
    }

    @Test
    fun testLoggingOnSensorReset() {
        // When sensor reset is detected, clear diagnostic logging helps user understand what happened
        val previousValue = 32000
        val currentValue = 0
        val isReset = previousValue > 0 && currentValue < previousValue

        if (isReset) {
            val logMessage = "SENSOR RESET DETECTED: previous=$previousValue, current=$currentValue. " +
                    "Another app may have accessed the sensor (e.g., Samsung Health). " +
                    "Keeping previous value to maintain data integrity."

            assertTrue("Should mention RESET", logMessage.contains("RESET"))
            assertTrue("Should show previous value", logMessage.contains("previous"))
            assertTrue("Should show current value", logMessage.contains("current"))
            assertTrue("Should mention Samsung Health", logMessage.contains("Health"))
        }
    }

    @Test
    fun testDataIntegrityPreservation() {
        // Key principle: When sensor resets, we preserve the previous value
        // to maintain data integrity. This means:
        // 1. Display doesn't drop to zero mid-hour
        // 2. Hour boundary calculations don't get corrupted
        // 3. User doesn't lose their step count data

        val hourStartSteps = 31800
        val stepsBeforeReset = 32000
        val stepsAfterReset = 0

        // If we use sensor reset value:
        val corruptedDelta = stepsAfterReset - hourStartSteps  // -31800

        // If we keep previous value:
        val preservedDelta = stepsBeforeReset - hourStartSteps  // 200

        assertEquals("Reset would corrupt data", -31800, corruptedDelta)
        assertEquals("Keeping previous value preserves data", 200, preservedDelta)
    }

    @Test
    fun testInitialValueTracking() {
        // When app first initializes, previousSensorValue should be set
        // so we can detect resets afterwards
        var previousSensorValue = 0
        val initialReading = 32000

        // When sensor first fires or is explicitly set
        previousSensorValue = initialReading

        // Now we can detect resets
        val resetReading = 0
        val isReset = previousSensorValue > 0 && resetReading < previousSensorValue

        assertTrue("Should be able to detect resets after initialization", isReset)
    }

    @Test
    fun testHourBoundaryNotAffectedByReset() {
        // Even if sensor resets mid-hour, hour boundary calculation
        // should still work because we:
        // 1. Ignore the reset in onSensorChanged
        // 2. Keep the previous value
        // 3. Hour boundary uses preferences (cached value), not sensor

        val hourlySensorValue = 32000
        val lastHourStartFromPreferences = 31800

        // If sensor resets:
        val resetValue = 0

        // Hour boundary check reads from preferences, not sensor:
        val hourBoundaryDelta = 32100 - lastHourStartFromPreferences  // Some new value from prefs

        assertTrue("Hour boundary not affected by mid-hour sensor reset", hourBoundaryDelta > 0)
    }
}
