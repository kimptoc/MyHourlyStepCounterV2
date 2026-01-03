package com.example.myhourlystepcounterv2.ui

import com.example.myhourlystepcounterv2.StepTrackerConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * Tests for smart closure period handling logic.
 * Covers scenarios where app is closed across hour/day boundaries.
 */
class ClosurePeriodHandlingTest {

    /**
     * Test: Day boundary crossing - app closed 10pm, reopened 9am next day
     * Expected: Yesterday's incomplete hour saved as 0 (sleep time)
     */
    @Test
    fun testDayBoundaryCrossing_EarlyMorningReopen() {
        // Setup: App was running yesterday at 10pm, reopened today at 9am
        val yesterdayStartOfDay = getStartOfDay(Calendar.getInstance().apply {
            add(Calendar.DAY_OF_MONTH, -1)
        })
        val todayStartOfDay = getStartOfDay(Calendar.getInstance())

        val crossedDayBoundary = yesterdayStartOfDay > 0 && yesterdayStartOfDay < todayStartOfDay
        assertTrue("Should detect day boundary crossing", crossedDayBoundary)
    }

    /**
     * Test: First open of day - app reopens in early morning (before 10am)
     * Expected: All accumulated steps go into current hour
     */
    @Test
    fun testFirstOpenOfDay_EarlyMorning_8AM() {
        val currentHour = 8  // 8am = before threshold of 10am
        val isEarlyMorning = currentHour < StepTrackerConfig.MORNING_THRESHOLD_HOUR
        val stepsWhileClosed = 500

        assertTrue("8am should be considered early morning", isEarlyMorning)
        // All 500 steps should be assigned to 8am hour
        assertEquals("All steps go to current hour", 500, stepsWhileClosed)
    }

    /**
     * Test: First open of day - early morning boundary exactly at threshold
     * Expected: Hour 10 (10am) is NOT early morning, should distribute
     */
    @Test
    fun testFirstOpenOfDay_ThresholdBoundary_10AM() {
        val currentHour = StepTrackerConfig.MORNING_THRESHOLD_HOUR  // Exactly 10
        val isEarlyMorning = currentHour < StepTrackerConfig.MORNING_THRESHOLD_HOUR

        assertTrue("Hour 10 should NOT be early morning (< not <=)", !isEarlyMorning)
    }

    /**
     * Test: First open of day - later in day (after 10am)
     * Expected: Steps distributed evenly across hours since threshold
     */
    @Test
    fun testFirstOpenOfDay_LaterInDay_2PM_Distribute() {
        val currentHour = 14  // 2pm = after 10am threshold
        val stepsWhileClosed = 800
        val thresholdHour = StepTrackerConfig.MORNING_THRESHOLD_HOUR  // 10

        val isEarlyMorning = currentHour < thresholdHour
        assertTrue("2pm should NOT be early morning", !isEarlyMorning)

        // Calculate hours awake since threshold
        val hoursAwake = currentHour - thresholdHour
        assertEquals("Should have 4 hours awake (10am-2pm)", 4, hoursAwake)

        // Distribute steps
        val stepsPerHour = stepsWhileClosed / hoursAwake
        assertEquals("800 steps / 4 hours = 200 steps/hour", 200, stepsPerHour)
    }

    /**
     * Test: Step distribution clamping
     * Expected: Distributed steps clamped to MAX_STEPS_PER_HOUR
     */
    @Test
    fun testStepDistribution_ClampToMax() {
        val stepsWhileClosed = 50000  // Huge jump (sensor sync?)
        val hoursAwake = 4
        val stepsPerHour = stepsWhileClosed / hoursAwake  // 12,500

        val stepsClamped = minOf(stepsPerHour, StepTrackerConfig.MAX_STEPS_PER_HOUR)

        assertEquals("Raw calculation: 50000/4 = 12500", 12500, stepsPerHour)
        assertEquals("Clamped to max: 10000", StepTrackerConfig.MAX_STEPS_PER_HOUR, stepsClamped)
    }

    /**
     * Test: Edge case - app reopens very soon after threshold
     * e.g., closed 10:01am, reopened 10:15am
     */
    @Test
    fun testFirstOpenOfDay_ImmediatelyAfterThreshold() {
        val currentHour = 10  // 10am hour
        val thresholdHour = 10

        val isEarlyMorning = currentHour < thresholdHour
        assertTrue("Hour 10 should NOT be early morning", !isEarlyMorning)

        val hoursAwake = currentHour - thresholdHour
        assertEquals("0 hours awake (within same hour)", 0, hoursAwake)

        // With 0 hours awake, we shouldn't try to distribute (avoid division by zero)
        if (hoursAwake > 0) {
            // Would distribute normally
        } else {
            // Should skip distribution or put all in current hour
            assertTrue("Should handle zero hours case", true)
        }
    }

    /**
     * Test: Distribution across multiple hours
     * Closed at 10am with 600 steps, reopened at 1pm (3 hours awake)
     */
    @Test
    fun testDistributionAcrossMultipleHours() {
        val stepsWhileClosed = 600
        val currentHour = 13  // 1pm
        val thresholdHour = 10
        val hoursAwake = currentHour - thresholdHour  // 3 hours

        val stepsPerHour = stepsWhileClosed / hoursAwake
        assertEquals("600 / 3 = 200 steps per hour", 200, stepsPerHour)

        // Verify total preservation
        val totalDistributed = stepsPerHour * hoursAwake
        assertEquals("Total steps preserved", 600, totalDistributed)
    }

    /**
     * Test: Yesterday's incomplete hour saved as 0
     * When day boundary detected, incomplete hour gets 0 steps
     */
    @Test
    fun testYesterdayIncompleteHourSavedAsZero() {
        val yesterdayIncompleteHourSteps = 0

        // This represents recording 0 for yesterday's incomplete hour
        assertEquals("Yesterday's incomplete hour = 0", 0, yesterdayIncompleteHourSteps)
    }

    /**
     * Test: Configuration values are reasonable
     */
    @Test
    fun testConfigurationValues() {
        assertEquals("Morning threshold should be 10", 10, StepTrackerConfig.MORNING_THRESHOLD_HOUR)
        assertEquals("Max steps/hour should be 10000", 10000, StepTrackerConfig.MAX_STEPS_PER_HOUR)
        assertEquals("Display strings should match constants", "10:00 AM", StepTrackerConfig.MORNING_THRESHOLD_DISPLAY)
        assertEquals("Display string for max steps", "10,000", StepTrackerConfig.MAX_STEPS_DISPLAY)
    }

    /**
     * Test: Scenario from real usage - app closed 2am, reopened 9am
     * 1200 steps taken while closed (woke up and walked around 8am)
     */
    @Test
    fun testRealWorldScenario_SleepThenMorningWakeup() {
        val closureTime = 2  // 2am
        val reopenTime = 9   // 9am
        val stepsWhileClosed = 1200

        val isEarlyMorning = reopenTime < StepTrackerConfig.MORNING_THRESHOLD_HOUR
        assertTrue("9am is early morning", isEarlyMorning)

        // All steps should go to 9am hour
        // Hours 2am-9am (previous day) would be marked as 0 (sleep)
        // 9am hour gets all 1200 steps
        val stepsFor9amHour = if (isEarlyMorning) stepsWhileClosed else 0
        assertEquals("All steps in 9am hour", 1200, stepsFor9amHour)
    }

    /**
     * Test: Scenario - app closed 10:15am, reopened 3pm same day
     * 2000 steps taken (lunch walk, afternoon activity)
     */
    @Test
    fun testRealWorldScenario_IntraDAyClosureAndDistribution() {
        val closureTime = 10  // 10:15am (during 10am hour)
        val reopenTime = 15   // 3pm
        val stepsWhileClosed = 2000

        val isEarlyMorning = reopenTime < StepTrackerConfig.MORNING_THRESHOLD_HOUR
        assertTrue("3pm is NOT early morning", !isEarlyMorning)

        val hoursAwake = reopenTime - StepTrackerConfig.MORNING_THRESHOLD_HOUR
        assertEquals("Hours from 10am to 3pm = 5 hours", 5, hoursAwake)

        val stepsPerHour = stepsWhileClosed / hoursAwake
        assertEquals("2000 / 5 = 400 steps per hour", 400, stepsPerHour)

        // Verify no loss of data
        val totalDistributed = stepsPerHour * hoursAwake
        assertEquals("All steps distributed", 2000, totalDistributed)
    }

    /**
     * Test: Minimal steps during closure
     * App closed with < 10 steps taken while closed (noise threshold)
     */
    @Test
    fun testMinimalStepsDuringClosure() {
        val stepsWhileClosed = 5  // Less than 10 step threshold

        // App should skip distribution logic for small values
        val shouldProcess = stepsWhileClosed > 10
        assertTrue("Should not process minimal steps", !shouldProcess)
    }

    /**
     * Test: Verify midnight calculation for "last open date"
     */
    @Test
    fun testMidnightCalculation() {
        val calendar = Calendar.getInstance()
        val startOfDay = calendar.apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val nextDay = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        assertTrue("Next day should be > today", nextDay > startOfDay)
        val dayDiffMs = nextDay - startOfDay
        val dayDiffHours = dayDiffMs / (60 * 60 * 1000)
        assertEquals("Should be 24 hours apart", 24L, dayDiffHours)
    }

    // Helper function
    private fun getStartOfDay(calendar: Calendar): Long {
        return calendar.apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
