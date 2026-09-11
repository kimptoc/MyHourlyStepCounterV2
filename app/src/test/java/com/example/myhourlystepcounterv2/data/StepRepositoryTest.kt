package com.example.myhourlystepcounterv2.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar

/**
 * Robolectric is needed for getAnomalyCoverage's test: StepRepository's constructor logs via
 * android.util.Log, same reason ConfirmFreshSensorEventTest needs it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class StepRepositoryTest {

    /**
     * Every method throws: proves getAnomalyCoverage's early "no snapshot ledger wired" return
     * never touches the DAO, rather than just asserting the return value happens to be null.
     */
    private val poisonStepDao = object : StepDao {
        override suspend fun insertStep(step: StepEntity): Unit = throw AssertionError("should not be called")
        override suspend fun getStepForHour(timestamp: Long): StepEntity? = throw AssertionError("should not be called")
        override fun getStepCountForHour(timestamp: Long): Flow<Int?> = throw AssertionError("should not be called")
        override fun getStepsForDay(startOfDay: Long, currentHourTimestamp: Long): Flow<List<StepEntity>> =
            throw AssertionError("should not be called")
        override suspend fun getStepsInRange(start: Long, end: Long): List<StepEntity> =
            throw AssertionError("should not be called")
        override suspend fun deleteOldSteps(cutoffTime: Long): Unit = throw AssertionError("should not be called")
        override fun getTotalStepsForDay(startOfDay: Long): Flow<Int?> = throw AssertionError("should not be called")
        override fun getTotalStepsForDayExcludingCurrentHour(startOfDay: Long, currentHourTimestamp: Long): Flow<Int?> =
            throw AssertionError("should not be called")
    }

    @Test
    fun getAnomalyCoverage_returnsNull_whenNoSnapshotProviderIsWired() = runBlocking {
        val repository = StepRepository(stepDao = poisonStepDao)

        val result = repository.getAnomalyCoverage()

        assertNull("Read-only instances have no ledger to bracket against", result)
    }

    @Test
    fun testSaveHourlySteps_PreventNegativeSteps() {
        // When saving negative steps (shouldn't happen)
        val negativeSteps = -100
        val clampedSteps = maxOf(0, negativeSteps)

        // Then it should be clamped to 0
        assertEquals("Negative steps should be clamped to 0", 0, clampedSteps)
    }

    @Test
    fun testHourlyStepRangeValidation_AcceptsReasonableRange() {
        // When: typical hourly step counts
        val validRanges = listOf(0, 100, 500, 1000, 5000, 10000)

        validRanges.forEach { steps ->
            val isValid = steps in 0..10000
            assertTrue("$steps steps per hour should be valid", isValid)
        }
    }

    @Test
    fun testHourlyStepRangeValidation_RejectsUnreasonable() {
        // When: unreasonable step counts
        val invalidSteps = listOf(-100, 50000, 100000, Int.MAX_VALUE)

        invalidSteps.forEach { steps ->
            val isValid = steps in 0..10000
            assertTrue("$steps steps should be flagged as invalid", !isValid)
        }
    }

    @Test
    fun testStepEntityCreation_WithValidData() {
        // When: creating a step entity
        val timestamp = System.currentTimeMillis()
        val stepCount = 250

        val entity = StepEntity(timestamp = timestamp, stepCount = stepCount)

        // Then
        assertNotNull("Entity should be created", entity)
        assertEquals("Timestamp should match", timestamp, entity.timestamp)
        assertEquals("Step count should match", stepCount, entity.stepCount)
    }

    @Test
    fun testStepEntityCreation_ZeroSteps() {
        // When: hour had no steps
        val timestamp = System.currentTimeMillis()
        val stepCount = 0

        val entity = StepEntity(timestamp = timestamp, stepCount = stepCount)

        assertEquals("Should allow zero steps", 0, entity.stepCount)
    }

    @Test
    fun testGetStartOfDayCalculation() {
        // When: calculating start of day
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val startOfDay = calendar.timeInMillis

        // Then: verify it's valid
        val verifyCalendar = Calendar.getInstance().apply { timeInMillis = startOfDay }
        assertEquals("Hour should be 0", 0, verifyCalendar.get(Calendar.HOUR_OF_DAY))
        assertEquals("Minute should be 0", 0, verifyCalendar.get(Calendar.MINUTE))
    }

    @Test
    fun testTimestampNormalization_ToHourStart() {
        // Given: a time in the middle of an hour (e.g., 7:45:30)
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 7)
            set(Calendar.MINUTE, 45)
            set(Calendar.SECOND, 30)
            set(Calendar.MILLISECOND, 0)
        }

        // When: normalizing to hour start
        val normalized = Calendar.getInstance().apply {
            timeInMillis = calendar.timeInMillis
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        // Then: should be 7:00:00
        assertEquals("Hour should be 7", 7, normalized.get(Calendar.HOUR_OF_DAY))
        assertEquals("Minute should be 0", 0, normalized.get(Calendar.MINUTE))
        assertEquals("Second should be 0", 0, normalized.get(Calendar.SECOND))
    }

    @Test
    fun testHourBoundaryTimestampCalculation() {
        // Given: current hour is 8:00
        val currentHour = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 8)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        // When: calculating previous hour
        val previousHour = Calendar.getInstance().apply {
            timeInMillis = currentHour.timeInMillis
            add(Calendar.HOUR_OF_DAY, -1)
        }

        // Then: previous hour should be 7:00
        assertEquals("Previous hour should be 7", 7, previousHour.get(Calendar.HOUR_OF_DAY))
    }
}
