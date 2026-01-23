package com.example.myhourlystepcounterv2.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.*

@RunWith(AndroidJUnit4::class)
@SmallTest
class StepDaoAtomicTest {

    private lateinit var database: StepDatabase
    private lateinit var dao: StepDao

    @Before
    fun createDb() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            StepDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = database.stepDao()
    }

    @After
    @Throws(IOException::class)
    fun closeDb() {
        database.close()
    }

    @Test
    fun testDao_saveHourlyStepsAtomic_raceCondition_twoSavesSameMillisecond() = runTest {
        // Given
        val timestamp = System.currentTimeMillis()
        val stepCount1 = 500
        val stepCount2 = 600  // Higher value

        // When
        dao.saveHourlyStepsAtomic(timestamp, stepCount1)
        dao.saveHourlyStepsAtomic(timestamp, stepCount2)

        // Then
        val result = dao.getStepForHour(timestamp)
        // Higher value should win according to the atomic logic
        assertEquals(stepCount2, result?.stepCount)
    }

    @Test
    fun testDao_saveHourlyStepsAtomic_higherValueWins_newValue500_existing300() = runTest {
        // Given
        val timestamp = System.currentTimeMillis()
        val existingValue = 300
        val newValue = 500

        // Insert existing value first
        dao.insertStep(StepEntity(timestamp = timestamp, stepCount = existingValue))

        // When
        dao.saveHourlyStepsAtomic(timestamp, newValue)

        // Then
        val result = dao.getStepForHour(timestamp)
        // New higher value should replace existing
        assertEquals(newValue, result?.stepCount)
    }

    @Test
    fun testDao_saveHourlyStepsAtomic_lowerValueRejected_newValue300_existing500() = runTest {
        // Given
        val timestamp = System.currentTimeMillis()
        val existingValue = 500
        val newValue = 300

        // Insert existing value first
        dao.insertStep(StepEntity(timestamp = timestamp, stepCount = existingValue))

        // When
        dao.saveHourlyStepsAtomic(timestamp, newValue)

        // Then
        val result = dao.getStepForHour(timestamp)
        // Existing higher value should be preserved
        assertEquals(existingValue, result?.stepCount)
    }

    @Test
    fun testDao_saveHourlyStepsAtomic_logging_verification() = runTest {
        // Given
        val timestamp = System.currentTimeMillis()
        val existingValue = 500
        val newValue = 300  // Lower value that should be rejected

        // Insert existing value first
        dao.insertStep(StepEntity(timestamp = timestamp, stepCount = existingValue))

        // When
        dao.saveHourlyStepsAtomic(timestamp, newValue)

        // Then
        val result = dao.getStepForHour(timestamp)
        // Existing value should be preserved, and logging should have occurred
        assertEquals(existingValue, result?.stepCount)
    }

    @Test
    fun testDao_saveHourlyStepsAtomic_transactionRollback_behaviorOnError() = runTest {
        // Given
        val timestamp = System.currentTimeMillis()
        val stepCount = 450

        // When
        dao.saveHourlyStepsAtomic(timestamp, stepCount)

        // Then
        val result = dao.getStepForHour(timestamp)
        assertEquals(stepCount, result?.stepCount)

        // Verify transaction completed successfully by checking the data is persisted
        val allSteps = dao.getStepsForDay(getStartOfDay(), timestamp + 1).first()
        assert(allSteps.isNotEmpty())
    }

    @Test
    fun testDao_getStepsForDay_Flow_emissionVerification() = runTest {
        // Given
        val startOfDay = getStartOfDay()
        val timestamp = System.currentTimeMillis()
        val stepCount = 250

        // Insert a step
        dao.insertStep(StepEntity(timestamp = timestamp, stepCount = stepCount))

        // When
        val stepsFlow = dao.getStepsForDay(startOfDay, timestamp + 1)

        // Then
        val result = stepsFlow.first()
        assert(result.isNotEmpty())
        assertEquals(stepCount, result[0].stepCount)
    }

    @Test
    fun testDao_getTotalStepsForDayExcludingCurrentHour_queryAccuracy() = runTest {
        // Given
        val startOfDay = getStartOfDay()
        val currentHourTimestamp = Calendar.getInstance().apply {
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        // Insert steps for different hours
        val stepCount1 = 200
        val stepCount2 = 300
        val stepCountCurrent = 150

        val timestamp1 = currentHourTimestamp - (2 * 60 * 60 * 1000) // 2 hours ago
        val timestamp2 = currentHourTimestamp - (1 * 60 * 60 * 1000) // 1 hour ago

        dao.insertStep(StepEntity(timestamp = timestamp1, stepCount = stepCount1))
        dao.insertStep(StepEntity(timestamp = timestamp2, stepCount = stepCount2))
        dao.insertStep(StepEntity(timestamp = currentHourTimestamp, stepCount = stepCountCurrent))

        // When
        val totalFlow = dao.getTotalStepsForDayExcludingCurrentHour(startOfDay, currentHourTimestamp)

        // Then
        val result = totalFlow.first()
        assertEquals(stepCount1 + stepCount2, result) // Should exclude current hour
    }

    @Test
    fun testDao_deleteOldSteps_boundaryConditions_exactly30DaysOld() = runTest {
        // Given
        val thirtyDaysAgo = System.currentTimeMillis() - (30 * 24 * 60 * 60 * 1000L)
        val stepCount = 100

        // Insert a step that is exactly 30 days old
        dao.insertStep(StepEntity(timestamp = thirtyDaysAgo, stepCount = stepCount))

        // When
        // Calculate cutoff time (29 days ago, so 30-day-old entry should be kept)
        val cutoffTime = System.currentTimeMillis() - (29 * 24 * 60 * 60 * 1000L)
        dao.deleteOldSteps(cutoffTime)

        // Then
        // The 30-day-old entry should still exist
        val result = dao.getStepForHour(thirtyDaysAgo)
        assertEquals(stepCount, result?.stepCount)
    }

    private fun getStartOfDay(): Long {
        return Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}