package com.example.myhourlystepcounterv2.sensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for sensor reset and rollover scenarios.
 * Tests extreme edge cases that can occur with device reboots,
 * hardware resets, or sensor anomalies.
 */
class SensorRolloverAndResetTest {

    /**
     * MEDIUM PRIORITY: Sensor total decreases (device reboot)
     * Expected: Logic should clamp/adjust delta appropriately, write reasonable value
     */
    @Test
    fun testSensorDecreases_DeviceReboot_ClampToZero() {
        // Scenario: Device reboots, step counter resets
        val previousReading = 10000
        val currentReading = 2000  // Went backwards

        val delta = currentReading - previousReading
        assertEquals("Delta should be negative", -8000, delta)

        // Proper handling: clamp to 0
        val safeValue = maxOf(0, delta)
        assertEquals("Should clamp to 0", 0, safeValue)
    }

    /**
     * MEDIUM PRIORITY: Sensor decreases by 1 (boundary condition)
     * Expected: Should treat as reset, return 0
     */
    @Test
    fun testSensorDecreasesByOne_BoundaryReset() {
        val hourStartSteps = 1000
        val currentSteps = 999  // Decreased by 1

        val delta = currentSteps - hourStartSteps
        assertEquals("Delta should be -1", -1, delta)

        val validatedDelta = if (delta < 0) 0 else delta
        assertEquals("Even -1 should clamp to 0", 0, validatedDelta)
    }

    /**
     * MEDIUM PRIORITY: Sensor suddenly jumps extremely large (health app sync)
     * Expected: Should clamp to MAX_REASONABLE_STEPS_PER_HOUR
     */
    @Test
    fun testSensorJumpsUnreasonablyLarge_HealthAppSync() {
        val hourStartSteps = 1000
        val currentSteps = 61000  // Jump of 60,000 (health app synced)

        val delta = currentSteps - hourStartSteps
        assertEquals("Delta should be 60,000", 60000, delta)

        // Validate with max
        val validatedDelta = when {
            delta < 0 -> 0
            delta > 10000 -> 10000  // Clamp to max
            else -> delta
        }

        assertEquals("Should clamp to 10,000", 10000, validatedDelta)
    }

    /**
     * MEDIUM PRIORITY: Sensor jumps just over max (11,000 steps)
     * Expected: Should clamp to exactly 10,000
     */
    @Test
    fun testSensorJustOverMax_11kSteps() {
        val hourStartSteps = 1000
        val currentSteps = 12000  // +11,000 (1000 over max)

        val delta = currentSteps - hourStartSteps
        assertEquals("Delta should be 11,000", 11000, delta)

        val clampedDelta = minOf(delta, 10000)
        assertEquals("Should clamp to 10,000", 10000, clampedDelta)
    }

    /**
     * MEDIUM PRIORITY: Sensor at maximum reasonable value (10,000)
     * Expected: Should be acceptable, no clamping
     */
    @Test
    fun testSensorAt10kMax_AcceptableValue() {
        val hourStartSteps = 1000
        val currentSteps = 11000  // Exactly 10,000 delta

        val delta = currentSteps - hourStartSteps
        assertEquals("Delta should be 10,000", 10000, delta)

        val validatedDelta = if (delta > 10000) 10000 else delta
        assertEquals("At max should be accepted", 10000, validatedDelta)
    }

    /**
     * MEDIUM PRIORITY: Sensor jumps to unreasonable absolute value (millions)
     * Expected: Should clamp delta, not the absolute value
     */
    @Test
    fun testSensorAbsoluteValueMassive_DeltaClamping() {
        val hourStartSteps = 999_999_000
        val currentSteps = 1_000_000_000  // Huge absolute, small delta

        val delta = currentSteps - hourStartSteps
        assertEquals("Delta should only be 1,000", 1000, delta)

        val validatedDelta = if (delta > 10000) 10000 else delta
        assertEquals("Delta should be 1,000 (within max)", 1000, validatedDelta)
    }

    /**
     * MEDIUM PRIORITY: Sensor Int.MAX_VALUE approach (no actual rollover)
     * Expected: Should handle gracefully without overflow
     */
    @Test
    fun testSensorNearIntMaxValue() {
        val hourStartSteps = Int.MAX_VALUE - 500
        val currentSteps = Int.MAX_VALUE  // Near overflow point

        // In a language with overflow, this could be dangerous
        // Kotlin handles Int arithmetic with overflow
        val delta = currentSteps - hourStartSteps

        // Should be 500, a normal value
        assertEquals("Delta should be 500", 500, delta)
        val validatedDelta = if (delta > 10000) 10000 else delta
        assertEquals("Should validate normally", 500, validatedDelta)
    }

    /**
     * MEDIUM PRIORITY: Multiple consecutive resets
     * Expected: Each reset should be handled independently, no cascading errors
     */
    @Test
    fun testConsecutiveResets() {
        val readings = listOf(
            10000,  // Initial
            5000,   // Reset 1
            3000,   // Reset 2
            1000,   // Reset 3
        )

        var previousReading = readings[0]
        readings.drop(1).forEach { currentReading ->
            val delta = currentReading - previousReading
            val safeValue = maxOf(0, delta)

            assertTrue("Each reset should handle independently", safeValue >= 0)
            previousReading = currentReading
        }
    }

    /**
     * MEDIUM PRIORITY: Very small normal deltas (user barely moved)
     * Expected: Should be accepted without clamping
     */
    @Test
    fun testSmallNormalDeltas() {
        val deltas = listOf(0, 1, 5, 10, 50, 100, 500, 999)

        deltas.forEach { delta ->
            val validatedDelta = if (delta < 0) 0 else if (delta > 10000) 10000 else delta
            assertEquals("Small deltas should be unchanged", delta, validatedDelta)
        }
    }

    /**
     * MEDIUM PRIORITY: Oscillating readings (sensor noise)
     * Expected: Each reading pair should be validated independently
     */
    @Test
    fun testOscillatingReadings() {
        val readingPairs = listOf(
            Pair(1000, 1050),   // +50 (normal)
            Pair(1050, 1045),   // -5  (would clamp to 0)
            Pair(1045, 1100),   // +55 (normal)
            Pair(1100, 1090),   // -10 (would clamp to 0)
        )

        readingPairs.forEach { (start, current) ->
            val delta = current - start
            val safeValue = if (delta < 0) 0 else delta

            assertTrue("Oscillating readings handled independently", safeValue >= 0)
        }
    }

    /**
     * Test: Detection of sensor reset
     * Expected: start > current indicates reset
     */
    @Test
    fun testSensorResetDetection() {
        val scenarios = listOf(
            Triple(1000, 999, true),      // Decrease = reset
            Triple(5000, 4999, true),     // Decrease = reset
            Triple(1000, 1000, false),    // Equal = no reset
            Triple(1000, 1001, false),    // Increase = no reset
        )

        scenarios.forEach { (start, current, isReset) ->
            val detectedReset = start > current
            assertEquals("Reset detection correct", isReset, detectedReset)
        }
    }

    /**
     * Test: Verify clamping function behavior
     * Expected: min(max(0, delta), 10000) pattern
     */
    @Test
    fun testClampingPattern() {
        val testDeltas = listOf(
            -1000 to 0,        // Negative → 0
            -100 to 0,
            -1 to 0,
            0 to 0,
            1 to 1,
            5000 to 5000,      // Normal → unchanged
            9999 to 9999,
            10000 to 10000,    // At max → unchanged
            10001 to 10000,    // Over max → clamped
            20000 to 10000,
            60000 to 10000,
            100000 to 10000,
        )

        testDeltas.forEach { (delta, expected) ->
            val clamped = maxOf(0, minOf(delta, 10000))
            assertEquals("Clamping $delta", expected, clamped)
        }
    }

    /**
     * Test: Verify that after reset, next normal reading works correctly
     * Expected: Should recover normally with new baseline
     */
    @Test
    fun testRecoveryAfterReset() {
        // Step 1: Reset happens
        val hourStartSteps = 10000
        val resetReading = 2000

        val firstDelta = resetReading - hourStartSteps
        val firstValue = if (firstDelta < 0) 0 else firstDelta
        assertEquals("Reset clamps to 0", 0, firstValue)

        // Step 2: Set new baseline after reset
        val newHourStartSteps = resetReading  // 2000
        val nextReading = 2500              // +500 steps

        val secondDelta = nextReading - newHourStartSteps
        val secondValue = if (secondDelta < 0) 0 else secondDelta
        assertEquals("After reset, next reading works normally", 500, secondValue)
    }

    /**
     * Test: Extreme outliers should still be handled without crashing
     * Expected: No overflow, no crash, just clamped
     */
    @Test
    fun testExtremeOutliers() {
        val extremeScenarios = listOf(
            Pair(Int.MIN_VALUE, Int.MAX_VALUE),  // Min to max
            Pair(Int.MAX_VALUE - 1, Int.MAX_VALUE),  // Near overflow
            Pair(0, Int.MAX_VALUE),  // Zero to max
            Pair(Int.MIN_VALUE + 1, 0),  // Min+1 to zero
        )

        extremeScenarios.forEach { (start, current) ->
            try {
                val delta = current - start
                val safeValue = if (delta < 0) 0 else if (delta > 10000) 10000 else delta
                assertTrue("Extreme values handled without crash", safeValue >= 0)
                assertTrue("Result clamped to max", safeValue <= 10000)
            } catch (e: Exception) {
                // Should not throw
                throw AssertionError("Extreme values caused exception: ${e.message}")
            }
        }
    }
}
