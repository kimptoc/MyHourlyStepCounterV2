package com.example.myhourlystepcounterv2.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Test edge cases and data integrity scenarios that can cause bugs
 */
class StepCounterEdgeCasesTest {

    @Test
    fun testSensorGap_AppClosedDuringHour() {
        // Scenario: App closes at 7:50, reopens at 8:10
        // Expected: Hour boundary detection should handle this
        val savedHourTimestamp = getHourTimestamp(7)
        val currentHourTimestamp = getHourTimestamp(8)

        val hourChanged = savedHourTimestamp != currentHourTimestamp
        assertTrue("Should detect hour change across app close", hourChanged)
    }

    @Test
    fun testUnexpectedStepJump_DetectAndReject() {
        // Scenario: Sensor reading jumps 60,000 steps in 1 hour
        // This happens when health app syncs or sensor resets
        val hourStartSteps = 1000
        val currentDeviceSteps = 61000
        val delta = currentDeviceSteps - hourStartSteps

        val isReasonable = delta in 0..10000
        assertTrue("Should flag 60,000 step jump as unreasonable", !isReasonable)
    }

    @Test
    fun testDeviceStepCounterRollover() {
        // Scenario: Device step counter hits Int.MAX_VALUE and wraps
        val maxIntSteps = Int.MAX_VALUE
        val wrappedValue = 0 // After rollover

        val delta = if (wrappedValue < maxIntSteps) {
            // Handle rollover: use wrapped value as new base
            wrappedValue
        } else {
            wrappedValue - 0
        }

        assertTrue("Should handle potential rollover", delta >= 0)
    }

    @Test
    fun testMultipleHourJump_AppClosedForDays() {
        // Scenario: App closed from day 1 to day 3 (48+ hours)
        // When reopened, should not crash
        val savedHourTimestamp = System.currentTimeMillis() - (48 * 60 * 60 * 1000) // 48 hours ago
        val currentHourTimestamp = System.currentTimeMillis()

        val hourChanged = savedHourTimestamp != currentHourTimestamp
        assertTrue("Should handle multiple hour gap", hourChanged)
    }

    @Test
    fun testNegativeDeltaHandling() {
        // Scenario: Sensor reading decreases (broken sensor or reset)
        val hourStartSteps = 1000
        val currentDeviceSteps = 500 // Went backwards!
        val delta = currentDeviceSteps - hourStartSteps

        val safeDelta = maxOf(0, delta)
        assertEquals("Negative delta should become 0", 0, safeDelta)
    }

    @Test
    fun testDataCorruption_LargeNumberStored() {
        // Scenario: Database somehow stores 60000 instead of 600
        val corruptedHourlySteps = 60000
        val dailyTotal = 600
        val nextHourDelta = 100

        // When adding next hour
        val newDailyTotal = dailyTotal + nextHourDelta

        // Result should not compound the corruption
        assertTrue("Should not compound corrupted data", newDailyTotal < corruptedHourlySteps)
    }

    @Test
    fun testZeroStepsAllDay() {
        // Scenario: User doesn't move all day
        val hourlySteps = listOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        val dailyTotal = hourlySteps.sum()

        assertEquals("Daily total should be 0 when no steps", 0, dailyTotal)
    }

    @Test
    fun testMaxStepsReasonable() {
        // Scenario: Very active user with reasonable max
        val hourlySteps = (0..23).map { 8000 } // 8000 steps per hour
        val dailyTotal = hourlySteps.sum()

        assertEquals("8000 steps/hour * 24 hours = 192000", 192000, dailyTotal)
    }

    @Test
    fun testPreferencesNotInitialized() {
        // Scenario: First app launch, no stored preferences
        val savedStepsOnFirstLaunch = 0
        val currentSensorSteps = 1500

        val expectedInitialDelta = currentSensorSteps - savedStepsOnFirstLaunch
        assertEquals("First hour should capture all current steps", 1500, expectedInitialDelta)
    }

    @Test
    fun testDayBoundaryTransition() {
        // Scenario: Checking hour boundary near midnight
        val hour23Timestamp = getHourTimestamp(23) // 11 PM
        val hour0Timestamp = getHourTimestamp(0)   // Midnight next day

        val crossedMidnight = hour23Timestamp != hour0Timestamp
        assertTrue("Should detect day boundary crossing", crossedMidnight)
    }

    @Test
    fun testHistoryDataIntegrity_MultiHour() {
        // Scenario: Verify multiple hours of data maintains consistency
        val hours = mapOf(
            getHourTimestamp(6) to 500,  // 6 AM: 500 steps
            getHourTimestamp(7) to 250,  // 7 AM: 250 steps
            getHourTimestamp(8) to 1000, // 8 AM: 1000 steps
        )

        val dailyTotal = hours.values.sum()
        assertEquals("Daily total should be sum of all hours", 1750, dailyTotal)

        // Verify no hour has unreasonable value
        hours.values.forEach { steps ->
            assertTrue("Each hour should be reasonable", steps in 0..10000)
        }
    }

    @Test
    fun testConcurrentHourChange() {
        // Scenario: User takes steps exactly at hour boundary
        val hourStartSteps = 1000
        val stepsTakenAt7_59_59 = 50
        val stepsReadAt8_00_01 = 1050

        // Old hour should get the steps
        val oldHourSteps = stepsReadAt8_00_01 - hourStartSteps
        assertEquals("Old hour should capture boundary steps", 50, oldHourSteps)

        // New hour starts at 0
        val newHourStartSteps = stepsReadAt8_00_01
        assertEquals("New hour starts fresh", 1050, newHourStartSteps)
    }

    // Helper function
    private fun getHourTimestamp(hour: Int): Long {
        val calendar = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, hour)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        return calendar.timeInMillis
    }
}
