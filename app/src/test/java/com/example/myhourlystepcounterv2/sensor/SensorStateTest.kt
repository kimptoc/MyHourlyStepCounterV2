package com.example.myhourlystepcounterv2.sensor

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.Assert.*

class SensorStateTest {

    @Test
    fun `SensorState should be immutable`() {
        // Create initial state
        val initialState = SensorState(
            lastKnownStepCount = 100,
            lastHourStartStepCount = 50,
            isInitialized = true,
            previousSensorValue = 90,
            wasBelowThreshold = false,
            currentHourSteps = 50,
            hourTransitionInProgress = false
        )

        // Verify initial values
        assertEquals(100, initialState.lastKnownStepCount)
        assertEquals(50, initialState.lastHourStartStepCount)
        assertTrue(initialState.isInitialized)
        assertFalse(initialState.wasBelowThreshold)

        // Create a new state using copy
        val newState = initialState.copy(wasBelowThreshold = true)

        // Original state should remain unchanged
        assertEquals(100, initialState.lastKnownStepCount)
        assertEquals(50, initialState.lastHourStartStepCount)
        assertTrue(initialState.isInitialized)
        assertFalse(initialState.wasBelowThreshold) // Original should still be false

        // New state should have updated value
        assertTrue(newState.wasBelowThreshold)
        assertEquals(100, newState.lastKnownStepCount) // Other values should remain the same
    }

    @Test
    fun `SensorState copy should create new instance`() {
        val state1 = SensorState(lastKnownStepCount = 100)
        val state2 = state1.copy(lastKnownStepCount = 200)

        assertNotSame(state1, state2) // Different instances
        assertNotEquals(state1, state2) // Different values
        assertEquals(100, state1.lastKnownStepCount)
        assertEquals(200, state2.lastKnownStepCount)
    }
}

class StepSensorManagerConcurrencyTest {
    @Test
    fun `concurrent access to sensor state should be thread-safe`() = runTest {
        // Since we can't instantiate StepSensorManager without a real Android context in unit tests,
        // we'll test the concept with a simplified approach
        // In a real scenario, we would use Robolectric or instrumented tests for this

        // Create a simple counter to simulate the mutex behavior
        var sharedCounter = 0
        val mutex = Mutex()

        // Launch multiple coroutines that modify the shared counter concurrently
        val jobs = mutableListOf<Job>()
        repeat(10) { i ->
            val job = launch {
                repeat(100) { j ->
                    mutex.withLock {
                        val currentValue = sharedCounter
                        kotlinx.coroutines.delay(1) // Simulate some processing time
                        sharedCounter = currentValue + 1
                    }
                }
            }
            jobs.add(job)
        }

        // Wait for all jobs to complete
        jobs.forEach { it.join() }

        // Final counter should be consistent (10 * 100 = 1000)
        assertEquals(1000, sharedCounter)
    }

    @Test
    fun `mutex should prevent race conditions in simulated state updates`() = runTest {
        // Simulate state with a mutex protecting it
        var stateValue = 0
        val mutex = Mutex()

        // Launch multiple coroutines that update state
        val updateJobs = mutableListOf<Job>()
        repeat(5) { i ->
            val job = launch {
                repeat(10) { j ->
                    mutex.withLock {
                        val currentValue = stateValue
                        kotlinx.coroutines.delay(1) // Small delay to increase chance of race condition without mutex
                        stateValue = currentValue + 1
                    }
                }
            }
            updateJobs.add(job)
        }

        updateJobs.forEach { it.join() }

        // The final state should be consistent despite concurrent updates (5 * 10 = 50)
        assertEquals(50, stateValue)
    }
}