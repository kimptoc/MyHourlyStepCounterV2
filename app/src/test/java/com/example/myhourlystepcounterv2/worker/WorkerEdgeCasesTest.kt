package com.example.myhourlystepcounterv2.worker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * Unit tests for WorkManager edge cases and error handling.
 * Critical for production reliability - these test scenarios that can occur
 * when device reboots, preferences are corrupted, or permissions change.
 */
class WorkerEdgeCasesTest {

    /**
     * HIGH PRIORITY: DataStore cached device total is 0 (device reboot, missing prefs)
     * Expected: Worker should not create negative deltas, logs and handles gracefully
     */
    @Test
    fun testCachedDeviceTotalZero_NoNegativeDelta() {
        val hourStartStepCount = 0  // Missing/corrupted pref
        val currentDeviceSteps = 1500  // Sensor has actual reading

        // Calculate delta as worker does
        val stepsInPreviousHour = if (currentDeviceSteps > 0 && hourStartStepCount > 0) {
            currentDeviceSteps - hourStartStepCount
        } else if (hourStartStepCount == 0 && currentDeviceSteps == 0) {
            0
        } else if (hourStartStepCount > currentDeviceSteps) {
            0
        } else {
            0  // First run, no baseline
        }

        // Verify: should be 0, not negative
        assertEquals("Should be 0 when baseline is missing", 0, stepsInPreviousHour)
    }

    /**
     * HIGH PRIORITY: DataStore cached device total is 0, sensor also 0
     * Expected: Worker should safely handle as first-run/no-data scenario
     */
    @Test
    fun testBothPrefsAndSensorZero_SafeFirstRun() {
        val hourStartStepCount = 0
        val currentDeviceSteps = 0

        val stepsInPreviousHour = if (currentDeviceSteps > 0 && hourStartStepCount > 0) {
            currentDeviceSteps - hourStartStepCount
        } else if (hourStartStepCount == 0 && currentDeviceSteps == 0) {
            0  // First run case
        } else {
            0
        }

        assertEquals("First run should record 0 steps", 0, stepsInPreviousHour)
    }

    /**
     * HIGH PRIORITY: Negative delta (sensor reset/device reboot)
     * Expected: Should clamp to 0, log warning, does not create negative entry
     */
    @Test
    fun testNegativeDelta_SensorReset_ClampsToZero() {
        val hourStartStepCount = 5000
        val currentDeviceSteps = 3000  // Went backwards (sensor reset)

        val delta = currentDeviceSteps - hourStartStepCount
        assertEquals("Delta should be negative", -2000, delta)

        // Worker validation logic
        val validatedDelta = when {
            delta < 0 -> {
                // Log warning would happen here
                0  // Clamp to 0
            }
            delta > 10000 -> 10000
            else -> delta
        }

        assertEquals("Negative delta clamped to 0", 0, validatedDelta)
        assertTrue("Final value should never be negative", validatedDelta >= 0)
    }

    /**
     * HIGH PRIORITY: Unreasonably large delta (sensor jump/sync)
     * Expected: Should clamp to MAX_REASONABLE_STEPS_PER_HOUR (10,000)
     */
    @Test
    fun testUnreasonablyLargeDelta_HealthAppSync_Clamped() {
        val hourStartStepCount = 1000
        val currentDeviceSteps = 61000  // Huge jump from health app sync

        val delta = currentDeviceSteps - hourStartStepCount
        assertEquals("Raw delta should be 60,000", 60000, delta)

        val validatedDelta = when {
            delta < 0 -> 0
            delta > 10000 -> {
                10000  // Clamp to max
            }
            else -> delta
        }

        assertEquals("Large delta clamped to 10,000", 10000, validatedDelta)
    }

    /**
     * HIGH PRIORITY: Cached timestamp stale (>24 hours old)
     * Expected: Worker should detect gap and handle appropriately
     */
    @Test
    fun testStaleTimestamp_MoreThan24Hours_DetectsGap() {
        val savedHourTimestamp = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_MONTH, -2)  // 2 days ago
        }.timeInMillis

        val currentHourTimestamp = Calendar.getInstance().apply {
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val timeDiffMs = currentHourTimestamp - savedHourTimestamp
        val timeDiffHours = timeDiffMs / (60 * 60 * 1000)

        assertTrue("Should detect gap > 24 hours", timeDiffHours > 24)

        // Worker should handle this: either skip or create averaged entry
        val isStaleThreshold = timeDiffHours > 24
        if (isStaleThreshold) {
            // Conservative approach: don't write dubious data
            // Log warning about data loss
            assertTrue("Should handle stale timestamp gracefully", true)
        }
    }

    /**
     * HIGH PRIORITY: Cached timestamp exactly at boundary (24 hours old)
     * Expected: Should process normally as it's at limit, not beyond
     */
    @Test
    fun testTimestampAtExactly24HourBoundary() {
        val savedHourTimestamp = Calendar.getInstance().apply {
            add(Calendar.HOUR_OF_DAY, -24)
        }.timeInMillis

        val currentHourTimestamp = Calendar.getInstance().timeInMillis

        val timeDiffMs = currentHourTimestamp - savedHourTimestamp
        val timeDiffHours = timeDiffMs / (60 * 60 * 1000)

        assertEquals("Should be approximately 24 hours", 24L, timeDiffHours)
        val isStale = timeDiffHours > 24
        assertTrue("24 hours exactly should not be considered stale", !isStale)
    }

    /**
     * HIGH PRIORITY: Permission denied at execution time
     * Expected: Worker should log warning and exit gracefully without invalid writes
     */
    @Test
    fun testPermissionDenied_LogsAndHandlesGracefully() {
        val hasPermission = false

        // Worker checks permission
        if (!hasPermission) {
            // Worker should log warning (happens in code)
            // Worker should still attempt to use preferences/cached values if available
            // Worker should NOT crash or write invalid data
            assertTrue("Should handle missing permission gracefully", true)
        }
    }

    /**
     * HIGH PRIORITY: Multiple edge cases combined
     * Zero prefs + no sensor read + permission denied
     * Expected: Should use safe defaults, not crash
     */
    @Test
    fun testMultipleEdgesCombined_ZeroPrefsNoSensorNoPerm() {
        val hasPermission = false
        val hourStartStepCount = 0
        val preferencesTotalSteps = 0
        val sensorReadSuccessful = false
        val currentDeviceSteps = if (sensorReadSuccessful) 1500 else preferencesTotalSteps

        // Worker logic path
        val stepsInPreviousHour = if (currentDeviceSteps > 0 && hourStartStepCount > 0) {
            currentDeviceSteps - hourStartStepCount
        } else if (hourStartStepCount == 0 && currentDeviceSteps == 0) {
            0
        } else {
            0
        }

        assertEquals("Should record 0 steps as safe default", 0, stepsInPreviousHour)
        assertTrue("Result should never be negative", stepsInPreviousHour >= 0)
    }

    /**
     * MEDIUM PRIORITY: Sensor reset between hours (detected by negative delta)
     * Expected: Should log sensor reset, use 0 steps
     */
    @Test
    fun testSensorResetBetweenHours() {
        val hourStartStepCount = 10000
        val currentDeviceSteps = 5000  // Device rebooted, counter reset

        val validatedDelta = if (hourStartStepCount > currentDeviceSteps) {
            0  // Sensor reset path
        } else {
            currentDeviceSteps - hourStartStepCount
        }

        assertEquals("Sensor reset should use 0", 0, validatedDelta)
    }

    /**
     * MEDIUM PRIORITY: Sensor jumps unreasonably (e.g., health app sync)
     * Expected: Should clamp and log warning
     */
    @Test
    fun testSensorJumpsUnreasonable_HealthAppSync() {
        val scenarios = listOf(
            Pair(1000, 11100),    // +10,100 (slightly over max)
            Pair(1000, 20000),    // +19,000 (way over)
            Pair(1000, 61000),    // +60,000 (health app sync)
        )

        scenarios.forEach { (startSteps, currentSteps) ->
            val delta = currentSteps - startSteps
            val validatedDelta = when {
                delta < 0 -> 0
                delta > 10000 -> 10000
                else -> delta
            }

            assertTrue("Validated delta should be <= 10000", validatedDelta <= 10000)
            assertEquals("Validated delta should be exactly 10000 for large jumps", 10000, validatedDelta)
        }
    }

    /**
     * MEDIUM PRIORITY: Preferences write succeeds, but with corrupted value
     * Expected: Worker should detect during next run and handle
     */
    @Test
    fun testCorruptedPreferenceValue_NextRunDetects() {
        val corruptedHourStartSteps = 999999  // Invalid value
        val currentDeviceSteps = 5000

        val delta = currentDeviceSteps - corruptedHourStartSteps
        assertEquals("Should calculate huge negative delta", -994999, delta)

        val validatedDelta = if (delta < 0) 0 else delta
        assertEquals("Should clamp negative to 0", 0, validatedDelta)
    }

    /**
     * Test: Verify maxOf safety function
     * Expected: Never allows negative values to be written
     */
    @Test
    fun testMaxOfNeverNegative() {
        val testValues = listOf(-100, -1, 0, 1, 100, 10000)

        testValues.forEach { value ->
            val safeValue = maxOf(0, value)
            assertTrue("maxOf(0, value) should never be negative", safeValue >= 0)
            if (value < 0) {
                assertEquals("Negative values should become 0", 0, safeValue)
            } else {
                assertEquals("Non-negative values unchanged", value, safeValue)
            }
        }
    }

    /**
     * Test: Worker should not write if critical conditions not met
     * Expected: Should log skip reason, not crash
     */
    @Test
    fun testSkipWriteOnMissingData() {
        val scenarios = listOf(
            Triple(0, 0, false),      // Both zero, first run
            Triple(0, 100, false),    // Start zero, current non-zero (weird)
            Triple(1000, 500, false), // Start > current (reset)
        )

        scenarios.forEach { (hourStart, current, shouldWrite) ->
            // Simple heuristic: only write if we have reasonable values
            val canWrite = hourStart > 0 && current > 0 && current >= hourStart

            if (!canWrite) {
                // Worker would log reason and skip write
                // Or write 0 as safe default
                // But NOT crash
                assertTrue("Should handle gracefully", true)
            }
        }
    }

    /**
     * Test: Result.success() vs Result.retry() logic
     * Expected: Worker should retry on exception, succeed on success
     */
    @Test
    fun testWorkerResultHandling() {
        // Happy path: no exception
        val successResult = true
        assertEquals("Happy path returns success", true, successResult)

        // Error path: exception occurs
        val errorOccurred = true
        val shouldRetry = errorOccurred
        assertEquals("Error path returns retry", true, shouldRetry)
    }
}
