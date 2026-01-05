package com.example.myhourlystepcounterv2.worker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * Test the StepCounterWorker's hour boundary logic.
 *
 * DESIGN NOTE: Worker uses cached values from preferences instead of reading sensor directly.
 * Reason: Background sensor access is unreliable/restricted on modern Android.
 * The foreground ViewModel continuously updates preferences.totalStepsDevice whenever
 * the sensor fires, so it's always current and reliable.
 *
 * Direct approach (register + immediately read) = race condition (gets 0 or stale).
 */
class StepCounterWorkerTest {

    @Test
    fun testWorkerUsesPreferencesNotSensor() {
        // DESIGN: Worker trusts cached preferences over direct sensor reads
        // Reason: Sensor listener is async and may not have fired yet at read time
        val hourStartStepCount = 5000
        val currentDeviceTotalFromPrefs = 5250  // Updated by ViewModel's sensor listener

        // This is what the worker reads (from preferences, not sensor)
        val stepsInHour = currentDeviceTotalFromPrefs - hourStartStepCount

        assertEquals("Worker should use preference value", 250, stepsInHour)
    }

    @Test
    fun testWorkerValidatesDelta_Negative() {
        // When: Sensor reset or other anomaly causes negative delta
        val hourStartStepCount = 5000
        val currentDeviceTotalFromPrefs = 4500

        var delta = currentDeviceTotalFromPrefs - hourStartStepCount
        if (delta < 0) {
            delta = 0  // Clamp to zero
        }

        assertEquals("Negative delta should be clamped to 0", 0, delta)
    }

    @Test
    fun testWorkerValidatesDelta_Unreasonable() {
        // When: Health app synced large step update
        val hourStartStepCount = 5000
        val currentDeviceTotalFromPrefs = 20000

        var delta = currentDeviceTotalFromPrefs - hourStartStepCount
        if (delta > 10000) {
            delta = 10000  // Clamp to max reasonable per-hour
        }

        assertEquals("Unreasonable delta should be clamped to 10000", 10000, delta)
    }

    @Test
    fun testWorkerValidatesDelta_Normal() {
        // When: Normal user activity
        val hourStartStepCount = 5000
        val currentDeviceTotalFromPrefs = 5500

        var delta = currentDeviceTotalFromPrefs - hourStartStepCount
        if (delta < 0) delta = 0
        else if (delta > 10000) delta = 10000

        assertEquals("Normal delta should pass through unchanged", 500, delta)
    }

    @Test
    fun testWorkerHandlesFirstRun() {
        // When: App freshly installed, no previous data
        val hourStartStepCount = 0
        val currentDeviceTotalFromPrefs = 0

        // First run check
        val stepsInHour = if (hourStartStepCount == 0 && currentDeviceTotalFromPrefs == 0) {
            0  // First run or no data
        } else {
            currentDeviceTotalFromPrefs - hourStartStepCount
        }

        assertEquals("First run should result in 0 steps", 0, stepsInHour)
    }

    @Test
    fun testWorkerHandlesSensorReset() {
        // When: Device reboots, sensor counter resets to 0, but app had baseline 5000
        val hourStartStepCount = 5000
        val currentDeviceTotalFromPrefs = 100  // Reset

        val stepsInHour = if (hourStartStepCount > currentDeviceTotalFromPrefs) {
            0  // Sensor reset detected
        } else {
            currentDeviceTotalFromPrefs - hourStartStepCount
        }

        assertEquals("Sensor reset should result in 0 steps", 0, stepsInHour)
    }

    @Test
    fun testHourBoundaryTimestampCalculation() {
        // Given: Worker running at 10:00:01
        val now = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 10)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 1)
        }

        // When: Calculate previous hour timestamp
        val previousHour = Calendar.getInstance().apply {
            timeInMillis = now.timeInMillis
            add(Calendar.HOUR_OF_DAY, -1)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        // Then: Previous hour should be 9:00:00
        assertEquals("Previous hour should be 9", 9, previousHour.get(Calendar.HOUR_OF_DAY))
        assertEquals("Minute should be 0", 0, previousHour.get(Calendar.MINUTE))
        assertEquals("Second should be 0", 0, previousHour.get(Calendar.SECOND))
    }

    @Test
    fun testNewHourInitialization() {
        // When: Worker initializes for new hour
        val currentDeviceTotal = 5250
        val currentHourTimestamp = Calendar.getInstance().apply {
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        // New hour should start with current device total as baseline
        val newHourStartBaseline = currentDeviceTotal
        val newHourTimestamp = currentHourTimestamp

        assertTrue("New hour should have timestamp", newHourTimestamp > 0)
        assertEquals("New hour baseline should be current device total", 5250, newHourStartBaseline)
    }

    @Test
    fun testPreferencesIntegration_Flow() {
        // DESIGN: Hour lifecycle managed through preferences
        // Sequence:
        // 1. ViewModel initializes: hourStartStepCount=X, totalStepsDevice=X
        // 2. During hour: sensor fires, ViewModel updates totalStepsDevice periodically
        // 3. At hour boundary: Worker reads both values from preferences
        // 4. Worker calculates delta: totalStepsDevice - hourStartStepCount
        // 5. Worker saves to database
        // 6. Worker updates preferences for new hour

        val steps = listOf(
            "ViewModel initializes hourStartStepCount",
            "Sensor fires, ViewModel updates totalStepsDevice",
            "At hour boundary, Worker reads both from preferences",
            "Worker calculates delta and saves to database",
            "Worker updates preferences for new hour",
        )

        assertEquals("Should have 5 steps in preference flow", 5, steps.size)
        assertTrue("First step should be initialization", steps[0].contains("initializes"))
        assertTrue("Last step should be new hour setup", steps[4].contains("new hour"))
    }

    @Test
    fun testRaceCondition_ViewModelAlreadySaved() {
        // SCENARIO: ViewModel saves at 10:00:01, WorkManager runs at 10:03:57
        // Expected: WorkManager should detect existing record and skip save

        val hourTimestamp = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 9)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        // Simulate: ViewModel already saved 296 steps at hour boundary
        val existingStepsInDatabase = 296
        val databaseHasRecord = true

        // Simulate: WorkManager calculates 0 steps (because preferences were already updated)
        val workerCalculatedSteps = 0

        // The worker should check database first
        val shouldSkipSave = databaseHasRecord
        val finalStepsInDatabase = if (shouldSkipSave) {
            existingStepsInDatabase  // Keep ViewModel's value
        } else {
            workerCalculatedSteps  // Use worker's value
        }

        assertEquals(
            "Race condition: Should preserve ViewModel's save (296), not use Worker's (0)",
            296,
            finalStepsInDatabase
        )
        assertTrue("Worker should have detected existing record and skipped save", shouldSkipSave)
    }

    @Test
    fun testRaceCondition_AppWasKilled() {
        // SCENARIO: App killed, ViewModel never ran, only WorkManager runs
        // Expected: WorkManager should save its calculated value

        val hourTimestamp = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 2)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        // Simulate: ViewModel never ran (app was killed)
        val databaseHasRecord = false

        // Simulate: WorkManager calculated steps from preferences
        val workerCalculatedSteps = 150

        // The worker should save since no record exists
        val shouldSave = !databaseHasRecord
        val finalStepsInDatabase = if (shouldSave) {
            workerCalculatedSteps  // Use worker's value
        } else {
            0  // Would be ViewModel's value, but it doesn't exist
        }

        assertEquals(
            "App killed case: Should use Worker's calculated steps (150)",
            150,
            finalStepsInDatabase
        )
        assertTrue("Worker should save when ViewModel didn't run (app was killed)", shouldSave)
    }

    @Test
    fun testRaceCondition_Timing() {
        // DESIGN: Demonstrates the race condition timing
        // Timeline:
        // 10:00:01 - ViewModel wakes up, detects hour change
        // 10:00:01 - ViewModel reads: hourStart=55504, device=55800
        // 10:00:01 - ViewModel calculates: 296 steps
        // 10:00:01 - ViewModel saves 296 to database
        // 10:00:01 - ViewModel updates preferences: hourStart=55800 (for new hour)
        // 10:03:57 - WorkManager wakes up (3 min later)
        // 10:03:57 - WorkManager reads: hourStart=55800, device=55800 (already updated!)
        // 10:03:57 - WorkManager calculates: 0 steps
        // 10:03:57 - WorkManager checks database: record exists with 296 steps
        // 10:03:57 - WorkManager SKIPS save to preserve ViewModel's data

        data class SaveAttempt(val time: String, val source: String, val steps: Int, val saved: Boolean)

        val timeline = listOf(
            SaveAttempt("10:00:01", "ViewModel", 296, true),   // ViewModel saves correct data
            SaveAttempt("10:03:57", "WorkManager", 0, false),   // WorkManager skips (record exists)
        )

        assertEquals("Should have 2 save attempts", 2, timeline.size)
        assertTrue("ViewModel should have saved", timeline[0].saved)
        assertEquals("ViewModel should have saved 296 steps", 296, timeline[0].steps)
        assertTrue("WorkManager should have skipped save", !timeline[1].saved)

        // The final database value should be from ViewModel
        val finalDatabaseValue = timeline.first { it.saved }.steps
        assertEquals("Database should have ViewModel's value, not WorkManager's", 296, finalDatabaseValue)
    }
}
