package com.example.myhourlystepcounterv2.sensor

import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class StepSensorSyncWaitTest {

    @Test
    fun waitForFreshSensorEvent_returnsTrue_whenNewerEventArrivesWithinTimeout() = runTest {
        val state = MutableStateFlow(SensorState(lastSensorEventTimeMs = 100L))

        val deferred = async {
            waitForFreshSensorEvent(
                sensorState = state,
                timestampMs = 150L,
                timeoutMs = 1_000L
            )
        }

        advanceTimeBy(100)
        state.value = SensorState(lastSensorEventTimeMs = 200L)
        advanceUntilIdle()

        assertTrue(deferred.await())
    }

    @Test
    fun waitForFreshSensorEvent_returnsFalse_whenNoFreshEventArrivesBeforeTimeout() = runTest {
        val state = MutableStateFlow(SensorState(lastSensorEventTimeMs = 100L))

        val result = waitForFreshSensorEvent(
            sensorState = state,
            timestampMs = 150L,
            timeoutMs = 100L
        )

        assertFalse(result)
    }
}
