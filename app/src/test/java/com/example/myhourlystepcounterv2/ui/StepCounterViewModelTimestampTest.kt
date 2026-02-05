package com.example.myhourlystepcounterv2.ui

import com.example.myhourlystepcounterv2.StepTrackerConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StepCounterViewModelTimestampTest {
    @Test
    fun shouldSyncTimestamp_whenSensorInitializedAndValid() {
        val result = StepCounterViewModel.shouldSyncTimestamp(
            sensorInitialized = true,
            sensorBaseline = 1000,
            sensorCurrentSteps = 120,
            maxStepsPerHour = StepTrackerConfig.MAX_STEPS_PER_HOUR
        )

        assertTrue(result)
    }

    @Test
    fun shouldNotSyncTimestamp_whenSensorNotInitialized() {
        val result = StepCounterViewModel.shouldSyncTimestamp(
            sensorInitialized = false,
            sensorBaseline = 1000,
            sensorCurrentSteps = 120,
            maxStepsPerHour = StepTrackerConfig.MAX_STEPS_PER_HOUR
        )

        assertFalse(result)
    }

    @Test
    fun shouldNotSyncTimestamp_whenBaselineZero() {
        val result = StepCounterViewModel.shouldSyncTimestamp(
            sensorInitialized = true,
            sensorBaseline = 0,
            sensorCurrentSteps = 120,
            maxStepsPerHour = StepTrackerConfig.MAX_STEPS_PER_HOUR
        )

        assertFalse(result)
    }

    @Test
    fun shouldNotSyncTimestamp_whenBaselineNegative() {
        val result = StepCounterViewModel.shouldSyncTimestamp(
            sensorInitialized = true,
            sensorBaseline = -1,
            sensorCurrentSteps = 120,
            maxStepsPerHour = StepTrackerConfig.MAX_STEPS_PER_HOUR
        )

        assertFalse(result)
    }

    @Test
    fun shouldNotSyncTimestamp_whenStepsTooHigh() {
        val result = StepCounterViewModel.shouldSyncTimestamp(
            sensorInitialized = true,
            sensorBaseline = 1000,
            sensorCurrentSteps = StepTrackerConfig.MAX_STEPS_PER_HOUR + 1,
            maxStepsPerHour = StepTrackerConfig.MAX_STEPS_PER_HOUR
        )

        assertFalse(result)
    }

    @Test
    fun shouldNotSyncTimestamp_whenStepsNegative() {
        val result = StepCounterViewModel.shouldSyncTimestamp(
            sensorInitialized = true,
            sensorBaseline = 1000,
            sensorCurrentSteps = -5,
            maxStepsPerHour = StepTrackerConfig.MAX_STEPS_PER_HOUR
        )

        assertFalse(result)
    }
}
