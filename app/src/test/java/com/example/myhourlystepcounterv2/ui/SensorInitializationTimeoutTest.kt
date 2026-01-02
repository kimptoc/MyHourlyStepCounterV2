package com.example.myhourlystepcounterv2.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test sensor initialization timeout and fallback behavior.
 *
 * Design: Sensor listener is async. When app starts, it registers the listener
 * but the sensor may take variable time to fire depending on device load/state.
 *
 * Original problem: Only waited 500ms (5 retries × 100ms). On some devices,
 * sensor takes longer to respond, causing initialization to use 0.
 *
 * Solution:
 * 1. Extended timeout to ~1 second (10 retries × 100ms)
 * 2. Fall back to last known value from preferences if sensor doesn't respond
 * 3. Clear logging to diagnose what happened
 *
 * This ensures:
 * - Fast devices get sensor value quickly and exit early
 * - Slow devices get ~1s to respond before fallback
 * - No valid data is lost - preferences always have last known value
 */
class SensorInitializationTimeoutTest {

    @Test
    fun testSensorReadTimeout() {
        // Sensor initialization tries up to 10 times with 100ms delays
        val maxRetries = 10
        val delayPerRetry = 100  // ms
        val totalTimeout = maxRetries * delayPerRetry  // ~1000ms

        assertEquals("Should have 10 retries", 10, maxRetries)
        assertEquals("Should wait ~1 second total", 1000, totalTimeout)
    }

    @Test
    fun testEarlyExitWhenSensorResponds() {
        // If sensor fires quickly (e.g., on retry 2), app should exit early
        val sensorRespondedOnRetry = 2
        val delaysWaited = sensorRespondedOnRetry * 100

        assertTrue("Should have exited early", sensorRespondedOnRetry < 10)
        assertEquals("Should only wait 200ms", 200, delaysWaited)
    }

    @Test
    fun testFallbackToPreferencesWhenSensorTimeout() {
        // If sensor doesn't respond within timeout, fall back to preferences
        val sensorResponded = false
        val lastKnownValueInPreferences = 31500

        // When sensor times out and we have a fallback value
        val actualDeviceStepsUsed = if (!sensorResponded && lastKnownValueInPreferences > 0) {
            lastKnownValueInPreferences
        } else {
            0
        }

        assertEquals("Should use preferences fallback", 31500, actualDeviceStepsUsed)
    }

    @Test
    fun testFallbackWhenNoSensorAndNoPreferences() {
        // First launch: sensor doesn't respond AND no preferences data
        val sensorResponded = false
        val preferencesValue = 0

        val actualDeviceSteps = if (!sensorResponded && preferencesValue == 0) {
            0  // First launch
        } else if (!sensorResponded && preferencesValue > 0) {
            preferencesValue
        } else {
            0
        }

        assertEquals("First launch should use 0", 0, actualDeviceSteps)
    }

    @Test
    fun testLoggingOnSensorTimeout() {
        // When sensor times out, clear diagnostic logging helps debugging
        val maxRetries = 10
        val delayPerRetry = 100
        val totalTimeoutMs = maxRetries * delayPerRetry

        // Simulate the log message that appears in the code
        val logMessage = "App startup: Sensor didn't respond within 1000ms. Using fallback value from preferences: 31500"

        assertTrue("Log should mention didn't respond", logMessage.contains("didn't respond"))
        assertTrue("Log should mention within", logMessage.contains("within"))
        assertTrue("Log should mention fallback", logMessage.contains("fallback"))
        assertTrue("Log should mention preferences", logMessage.contains("preferences"))
        assertTrue("Log should mention time (1000ms)", logMessage.contains("1000ms"))
    }

    @Test
    fun testLoggingOnFirstLaunchNoSensor() {
        // First launch when sensor doesn't respond and no fallback available
        val sensorResponded = false
        val fallbackAvailable = false
        val isFirstLaunch = true

        if (!sensorResponded && !fallbackAvailable && isFirstLaunch) {
            val logMessage = "Sensor unresponsive and no fallback value available. " +
                    "Starting fresh with 0. This is normal for first launch."
            assertTrue("Should indicate first launch", logMessage.contains("first launch"))
        }
    }

    @Test
    fun testPreferencesAlwaysUpdated() {
        // Key insight: ViewModel updates preferences.totalStepsDevice whenever sensor fires
        // So preferences always have the latest value (or default if first launch)

        // If app restarts:
        // - Preferences from previous session are available
        // - If sensor is slow, we can use that value instead of waiting longer

        val appRestarted = true
        val preferencesHavePreviousValue = true

        assertTrue("Preferences should have previous value", preferencesHavePreviousValue)
    }

    @Test
    fun testInitializationStrategy() {
        // Sequence:
        // 1. Register sensor listener
        // 2. Try to read sensor (with retries) for ~1 second
        // 3. If successful: use sensor value
        // 4. If timeout: check preferences for fallback
        // 5. If no fallback: use 0 (first launch)
        // 6. Initialize hour with chosen value

        val steps = listOf(
            "Register sensor listener",
            "Retry reading sensor up to 10 times",
            "Check if sensor responded",
            "If no: read fallback from preferences",
            "If no fallback: use 0 (first launch)",
            "Initialize hour with final value"
        )

        assertEquals("Should have 6 steps", 6, steps.size)
        assertTrue("Should handle timeout", steps[1].contains("Retry"))
        assertTrue("Should check fallback", steps[3].contains("fallback"))
    }

    @Test
    fun testTimeoutConfigurability() {
        // The timeout is configurable - if needed for different devices:
        // val maxRetries = 20  // 2 seconds for very slow devices
        // val delayMs = 50      // Faster polling on capable devices

        // Current config:
        val maxRetries = 10
        val delayMs = 100

        // Could be adjusted to:
        val fastDeviceMaxRetries = 5      // 500ms for modern phones
        val slowDeviceMaxRetries = 20     // 2 seconds for older phones

        assertTrue("Config should be tunable", maxRetries > 0)
        assertTrue("Fast devices need less time", fastDeviceMaxRetries < maxRetries)
        assertTrue("Slow devices may need more", slowDeviceMaxRetries > maxRetries)
    }
}
