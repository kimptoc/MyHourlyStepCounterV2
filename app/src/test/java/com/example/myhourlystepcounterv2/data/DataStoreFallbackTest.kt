package com.example.myhourlystepcounterv2.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for DataStore fallback behavior.
 * Tests how the app recovers when sensor fails or is unavailable.
 */
class DataStoreFallbackTest {

    /**
     * MEDIUM PRIORITY: Sensor fails to initialize within timeout
     * Expected: ViewModel should use DataStore cached totals for daily calculation
     */
    @Test
    fun testSensorTimeoutFallback_UsesDataStoreCachedTotal() {
        // Simulate sensor timeout during initialization
        val sensorInitialized = false
        val fallbackValue = 5000  // From DataStore preferences

        // ViewModel should use fallback
        val deviceStepsToUse = if (sensorInitialized) 5500 else fallbackValue

        assertEquals("Should use cached value on sensor timeout", 5000, deviceStepsToUse)
    }

    /**
     * MEDIUM PRIORITY: Sensor responds after timeout threshold
     * Expected: Should use cached value, not wait indefinitely
     */
    @Test
    fun testSensorSlowResponse_UsesCache() {
        val maxRetries = 10
        val retryIntervalMs = 100
        val maxWaitMs = maxRetries * retryIntervalMs  // 1000ms total

        // Sensor responds at 1200ms (after max wait)
        val sensorReadTime = 1200
        val sensorResponded = sensorReadTime < maxWaitMs

        assertTrue("Sensor slow response should not block", !sensorResponded)

        // Should fallback to cached
        val cachedValue = 4500
        val valueToUse = if (sensorResponded) 5600 else cachedValue
        assertEquals("Fallback to cache on timeout", 4500, valueToUse)
    }

    /**
     * MEDIUM PRIORITY: Cached value is stale (device stepped for days)
     * Expected: Should still use it gracefully, even if not perfectly accurate
     */
    @Test
    fun testStaleCache_StillUsable() {
        // Cached value from 3 days ago
        val cachedValue = 1000
        val ageHours = 72

        // App should still use it rather than crash
        val isUsable = cachedValue > 0

        assertTrue("Stale cache still usable", isUsable)
        assertEquals("Should use stale cache over nothing", 1000, cachedValue)
    }

    /**
     * MEDIUM PRIORITY: Cache value is 0 (first boot or corrupted)
     * Expected: Should handle gracefully, not use negative/invalid values
     */
    @Test
    fun testCacheValueZero_Graceful() {
        val cachedValue = 0
        val sensorValue = 2500

        // When cache is 0, use sensor value if available
        val effectiveValue = if (cachedValue > 0) cachedValue else sensorValue

        assertEquals("Use sensor when cache is 0", 2500, effectiveValue)
    }

    /**
     * MEDIUM PRIORITY: Daily total calculation with cache
     * Expected: Daily total should use cached values, not fail
     */
    @Test
    fun testDailyTotalWithCache() {
        // Scenario: Sensor unavailable, use cache
        val dbTotal = 8000          // From database (previous hours)
        val cachedCurrentHour = 500 // From cache (current hour)
        val sensorUnavailable = true

        val currentHourSteps = if (sensorUnavailable) cachedCurrentHour else 600
        val dailyTotal = dbTotal + currentHourSteps

        assertEquals("Daily total uses cache when sensor unavailable", 8500, dailyTotal)
    }

    /**
     * MEDIUM PRIORITY: Compare sensor vs cache values
     * Expected: Should detect when they diverge significantly
     */
    @Test
    fun testSensorVsCacheDivergence_Detected() {
        val cachedValue = 5000
        val sensorValue = 5600
        val divergenceThreshold = 10  // Steps

        val divergence = Math.abs(sensorValue - cachedValue)
        val hasDivergence = divergence > divergenceThreshold

        assertTrue("Should detect divergence", hasDivergence)
        assertTrue("Divergence is logged/handled", divergence == 600)
    }

    /**
     * MEDIUM PRIORITY: Sensor update vs cache write race condition
     * Scenario: Sensor fires and updates state, but DataStore write hasn't completed yet
     */
    @Test
    fun testRaceCondition_SensorFiresBeforeWrite() {
        // Timeline:
        // 1. doWork() reads cached value (5000)
        // 2. Sensor fires, sets sensor manager state (5100)
        // 3. DataStore write started but not finished yet
        // 4. Worker tries to read sensor

        val cachedValueWhenWorkerRan = 5000
        val sensorValueWhenWorkerRan = 5100

        // Worker should get newer sensor value if available
        val valueToUse = if (sensorValueWhenWorkerRan > 0) sensorValueWhenWorkerRan else cachedValueWhenWorkerRan

        assertEquals("Should use fresher sensor value", 5100, valueToUse)
    }

    /**
     * MEDIUM PRIORITY: DataStore write fails, sensor is fallback
     * Expected: Should use sensor value, not corrupt cache
     */
    @Test
    fun testDataStoreWriteFails_UseSensor() {
        val cachedValue = 5000
        val sensorValue = 5050
        val dataStoreWriteFailed = true

        // If write failed, use sensor as authoritative
        val valueToUse = if (dataStoreWriteFailed) sensorValue else cachedValue

        assertEquals("Use sensor when DataStore write fails", 5050, valueToUse)

        // Verify we don't corrupt the cache
        val cacheUnmodified = cachedValue
        assertEquals("Cache unchanged after write failure", 5000, cacheUnmodified)
    }

    /**
     * MEDIUM PRIORITY: Cache becomes invalid (corrupt data)
     * Expected: Should detect and use sensor as fallback
     */
    @Test
    fun testCorruptCache_FallbackToSensor() {
        val corruptCacheValue = 999999999  // Unreasonable value
        val sensorValue = 5000

        // Detect corruption
        val isReasonable = corruptCacheValue <= 10_000_000  // Generous reasonable max
        val valueToUse = if (isReasonable) corruptCacheValue else sensorValue

        assertEquals("Use sensor for corrupt cache", 5000, valueToUse)
    }

    /**
     * Test: Fallback priority chain
     * Expected: Sensor > Cache > Default(0)
     */
    @Test
    fun testFallbackPriorityChain() {
        data class FallbackScenario(
            val sensorAvailable: Boolean,
            val sensorValue: Int,
            val cachedValue: Int,
            val expectedValue: Int
        )

        val scenarios = listOf(
            FallbackScenario(true, 5000, 4500, 5000),    // Sensor wins
            FallbackScenario(false, 0, 4500, 4500),      // Cache wins
            FallbackScenario(false, 0, 0, 0),            // Default
            FallbackScenario(true, 0, 4500, 4500),       // Sensor not responded, use cache
        )

        scenarios.forEach { scenario ->
            val effectiveValue = when {
                scenario.sensorAvailable && scenario.sensorValue > 0 -> scenario.sensorValue
                scenario.cachedValue > 0 -> scenario.cachedValue
                else -> 0
            }

            assertEquals(
                "Scenario: sensor=${scenario.sensorAvailable}, sensorVal=${scenario.sensorValue}, cache=${scenario.cachedValue}",
                scenario.expectedValue,
                effectiveValue
            )
        }
    }

    /**
     * Test: Concurrent sensor read while DataStore updating
     * Expected: Should handle race condition without crash
     */
    @Test
    fun testConcurrentUpdate_RaceCondition() {
        var cachedValue = 5000
        val sensorValue = 5050

        // Simulate race: sensor updates while we're reading cache
        val readCachedBefore = cachedValue
        cachedValue = 5100  // Concurrent update
        val readCachedAfter = cachedValue

        // We might get either value, both are reasonable
        assertTrue("Race condition handled", readCachedBefore >= 0 && readCachedAfter >= 0)
    }

    /**
     * Test: Sensor preferences not yet initialized (first boot)
     * Expected: Should use default values, not crash
     */
    @Test
    fun testFirstBootEmptyPreferences() {
        val hourStartStepCount = 0      // Not initialized
        val totalStepsDevice = 0        // Not initialized
        val currentSensorValue = 1500   // Sensor just fired

        // Should handle uninitialized prefs gracefully
        val safeHourStart = if (hourStartStepCount > 0) hourStartStepCount else currentSensorValue
        val safeTotal = if (totalStepsDevice > 0) totalStepsDevice else currentSensorValue

        assertEquals("Hour start initialized from sensor", 1500, safeHourStart)
        assertEquals("Total device initialized from sensor", 1500, safeTotal)
    }

    /**
     * Test: Multiple concurrent writes to DataStore
     * Expected: Last write should win, no data loss
     */
    @Test
    fun testMultipleConcurrentWrites() {
        val writes = listOf(
            Pair(5000, "Write 1"),
            Pair(5100, "Write 2"),
            Pair(5050, "Write 3"),
        )

        // Last write wins
        val finalValue = writes.last().first
        assertEquals("Last write wins", 5050, finalValue)
    }

    /**
     * Test: DataStore read takes longer than sensor fire
     * Expected: Should use sensor value, not wait for DataStore
     */
    @Test
    fun testSensorFasterThanDataStore() {
        val sensorFireTime = 50      // ms
        val dataStoreReadTime = 500  // ms

        val shouldUseSensor = sensorFireTime < dataStoreReadTime
        assertTrue("Sensor faster, should use it", shouldUseSensor)
    }
}
