package com.example.myhourlystepcounterv2.services

import com.example.myhourlystepcounterv2.sensor.SensorState
import com.example.myhourlystepcounterv2.services.StepCounterForegroundService.Companion.confirmFreshSensorEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Issue #28: the checkpoint loop's FLUSH and RE_REGISTER branches both used to proceed after a
 * fixed blind delay regardless of whether a fresh sensor callback actually landed. Robolectric is
 * needed here (unlike StepSensorSyncWaitTest, which tests the underlying wait alone) because this
 * function also logs via android.util.Log.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ConfirmFreshSensorEventTest {

    @Test
    fun confirmFreshSensorEvent_returnsTrue_whenEventArrivesWithinTimeout() = runTest {
        val state = MutableStateFlow(SensorState(lastSensorEventTimeMs = 100L))

        val deferred = async {
            confirmFreshSensorEvent(
                sensorState = state,
                probeStart = 100L,
                timeoutMs = 1_000L,
                label = "test"
            )
        }

        advanceTimeBy(50)
        state.value = SensorState(lastSensorEventTimeMs = 200L)
        advanceUntilIdle()

        assertTrue(deferred.await())
    }

    @Test
    fun confirmFreshSensorEvent_returnsFalse_whenNoEventArrivesBeforeTimeout() = runTest {
        val state = MutableStateFlow(SensorState(lastSensorEventTimeMs = 100L))

        val result = confirmFreshSensorEvent(
            sensorState = state,
            probeStart = 100L,
            timeoutMs = 100L,
            label = "test"
        )

        assertFalse(result)
    }
}
