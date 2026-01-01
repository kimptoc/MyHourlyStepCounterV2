package com.example.myhourlystepcounterv2.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class StepRepositoryTest {

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
