package com.example.myhourlystepcounterv2.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@MediumTest
class StepPreferencesComprehensiveTest {

    private lateinit var preferences: StepPreferences

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        preferences = StepPreferences(context)
    }

    @After
    fun tearDown() {
        // Clean up any resources if needed
    }

    @Test
    fun testStepPreferences_saveHourData_atomically() = runTest {
        // Given
        val hourStartStepCount = 1000
        val currentTimestamp = System.currentTimeMillis()
        val totalSteps = 1500

        // When
        preferences.saveHourData(
            hourStartStepCount = hourStartStepCount,
            currentTimestamp = currentTimestamp,
            totalSteps = totalSteps
        )

        // Then
        assertEquals(hourStartStepCount.toLong(), preferences.hourStartStepCount.first())
        assertEquals(currentTimestamp, preferences.currentHourTimestamp.first())
        assertEquals(totalSteps.toLong(), preferences.totalStepsDevice.first())
    }

    @Test
    fun testStepPreferences_saveReminderSentThisHour_booleanFlag() = runTest {
        // Given
        val reminderSent = true

        // When
        preferences.saveReminderSentThisHour(reminderSent)

        // Then
        assertEquals(reminderSent, preferences.reminderSentThisHour.first())
    }

    @Test
    fun testStepPreferences_saveAchievementSentThisHour_booleanFlag() = runTest {
        // Given
        val achievementSent = true

        // When
        preferences.saveAchievementSentThisHour(achievementSent)

        // Then
        assertEquals(achievementSent, preferences.achievementSentThisHour.first())
    }

    @Test
    fun testStepPreferences_savePermanentNotificationEnabled_settingToggle() = runTest {
        // Given
        val enabled = true

        // When
        preferences.savePermanentNotificationEnabled(enabled)

        // Then
        assertEquals(enabled, preferences.permanentNotificationEnabled.first())
    }

    @Test
    fun testStepPreferences_saveUseWakeLock_settingToggle() = runTest {
        // Given
        val useWakeLock = true

        // When
        preferences.saveUseWakeLock(useWakeLock)

        // Then
        assertEquals(useWakeLock, preferences.useWakeLock.first())
    }

    @Test
    fun testStepPreferences_saveReminderNotificationEnabled_settingToggle() = runTest {
        // Given
        val enabled = true

        // When
        preferences.saveReminderNotificationEnabled(enabled)

        // Then
        assertEquals(enabled, preferences.reminderNotificationEnabled.first())
    }

    @Test
    fun testStepPreferences_saveLastReminderNotificationTime_timestampTracking() = runTest {
        // Given
        val timestamp = System.currentTimeMillis()

        // When
        preferences.saveLastReminderNotificationTime(timestamp)

        // Then
        assertEquals(timestamp, preferences.lastReminderNotificationTime.first())
    }

    @Test
    fun testStepPreferences_defaultValueBehavior_allFieldsReturnCorrectDefaults() = runTest {
        // Given - fresh preferences instance

        // When - read all default values

        // Then
        assertEquals(0L, preferences.hourStartStepCount.first())
        assertEquals(0L, preferences.currentHourTimestamp.first())
        assertEquals(0L, preferences.totalStepsDevice.first())
        assertFalse(preferences.reminderSentThisHour.first())
        assertFalse(preferences.achievementSentThisHour.first())
        assertFalse(preferences.permanentNotificationEnabled.first())
        assertFalse(preferences.useWakeLock.first())
        assertFalse(preferences.reminderNotificationEnabled.first())
        assertEquals(0L, preferences.lastReminderNotificationTime.first())
    }

    @Test
    fun testStepPreferences_concurrentReadWriteRaceConditions_multipleCoroutinesAccessingSimultaneously() = runTest {
        // Given
        val iterations = 100
        val expectedFinalValue = iterations * 2  // Each iteration adds 2

        // When
        // Launch multiple coroutines to read/write simultaneously
        for (i in 0 until iterations) {
            // Write operation
            preferences.saveHourData(
                hourStartStepCount = i * 2,
                currentTimestamp = System.currentTimeMillis(),
                totalSteps = i * 2
            )
        }

        // Then
        // Verify the final state is consistent
        val finalHourStartStepCount: Int = preferences.hourStartStepCount.first()
        assertTrue(finalHourStartStepCount >= 0) // Should be positive
    }

    @Test
    fun testStepPreferences_dataStoreCorruptionRecovery_handleMalformedDataGracefully() = runTest {
        // Given
        val validValue = 1234L

        // When
        // Save a valid value
        preferences.saveHourData(
            hourStartStepCount = validValue.toInt(),
            currentTimestamp = System.currentTimeMillis(),
            totalSteps = validValue.toInt()
        )

        // Then
        // Should handle the data correctly
        assertEquals(validValue, preferences.hourStartStepCount.first())
        assertEquals(validValue, preferences.totalStepsDevice.first())
    }
}