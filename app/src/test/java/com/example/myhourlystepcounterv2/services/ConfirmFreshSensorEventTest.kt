package com.example.myhourlystepcounterv2.services

import com.example.myhourlystepcounterv2.sensor.SensorState
import com.example.myhourlystepcounterv2.services.StepCounterForegroundService.Companion.confirmFreshSensorEvent
import com.example.myhourlystepcounterv2.services.StepCounterForegroundService.Companion.confirmFreshSensorReadingWithFallback
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

/**
 * Issue #36: on-device evidence found flush alone never confirmed (0/8) while re-registering the
 * listener always did (4/4), so HourBoundaryCloser's two flush sites now fall back to
 * reRegisterListener() when a flush doesn't confirm in time.
 *
 * Uses a fake `now` clock with small literal timestamps, same as ConfirmFreshSensorEventTest
 * above and StepSensorSyncWaitTest -- not System.currentTimeMillis(). An earlier version of this
 * test used the real clock and was flaky: under `runTest`, coroutine delays run on virtual time
 * while System.currentTimeMillis() is real wall-clock time, so a probe-start captured via the real
 * clock and a same-call "fresh" timestamp could land in the same real millisecond regardless of
 * virtual time simulated, tripping the strict '>' in waitForFreshSensorEvent. The fake clock
 * sidesteps that rather than papering over it with a timestamp fudge.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ConfirmFreshSensorReadingWithFallbackTest {

    @Test
    fun returnsTrue_withoutFallingBackToReRegister_whenFlushConfirmsImmediately() = runTest {
        val state = MutableStateFlow(SensorState(lastSensorEventTimeMs = 100L))
        var reRegisterCalled = false

        val result = confirmFreshSensorReadingWithFallback(
            sensorState = state,
            doFlush = { state.value = SensorState(lastSensorEventTimeMs = 300L); true },
            doReRegister = { reRegisterCalled = true },
            label = "test",
            now = { 200L }
        )

        assertTrue(result)
        assertFalse("Flush confirmed; re-register fallback should not have been attempted", reRegisterCalled)
    }

    @Test
    fun fallsBackToReRegister_andReturnsTrue_whenFlushTimesOutButReRegisterConfirms() = runTest {
        val state = MutableStateFlow(SensorState(lastSensorEventTimeMs = 100L))
        var reRegisterCalled = false
        var clock = 200L

        val result = confirmFreshSensorReadingWithFallback(
            sensorState = state,
            // Flush accepted (true) but delivers nothing -- simulates issue #36's evidence.
            doFlush = { true },
            doReRegister = {
                reRegisterCalled = true
                state.value = SensorState(lastSensorEventTimeMs = 5_000L)
            },
            label = "test",
            now = { clock.also { clock += 1 } }
        )

        assertTrue(result)
        assertTrue("Flush timed out; re-register fallback should have been attempted", reRegisterCalled)
    }

    @Test
    fun returnsFalse_afterAttemptingReRegisterFallback_whenNeitherConfirms() = runTest {
        val state = MutableStateFlow(SensorState(lastSensorEventTimeMs = 100L))
        var reRegisterCalled = false
        var clock = 200L

        val result = confirmFreshSensorReadingWithFallback(
            sensorState = state,
            doFlush = { true },
            doReRegister = { reRegisterCalled = true },
            label = "test",
            now = { clock.also { clock += 1 } }
        )

        assertFalse(result)
        assertTrue("Fallback should still have been attempted even though it also didn't confirm", reRegisterCalled)
    }

    @Test
    fun skipsFlushWait_andFallsBackImmediately_whenFlushIsRejectedByThePlatform() = runTest {
        // SensorManager.flush() returning false means no callback is coming at all -- issue #36
        // review: don't burn the full flush timeout waiting for one anyway.
        val state = MutableStateFlow(SensorState(lastSensorEventTimeMs = 100L))
        var reRegisterCalled = false
        var clock = 200L

        val result = confirmFreshSensorReadingWithFallback(
            sensorState = state,
            doFlush = { false },
            doReRegister = {
                reRegisterCalled = true
                state.value = SensorState(lastSensorEventTimeMs = 5_000L)
            },
            label = "test",
            now = { clock.also { clock += 1 } }
        )

        assertTrue(result)
        assertTrue("Rejected flush should fall back to re-register", reRegisterCalled)
    }

    @Test
    fun stillAttemptsFlush_butSkipsReRegister_whenSensorHasNeverReported() = runTest {
        // lastSensorEventTimeMs == 0 mirrors determineSensorAction's own
        // "still initializing, don't re-register" guard for the checkpoint loop (issue #36
        // review) -- but only for the re-register escalation. The flush attempt (and its wait)
        // still has to run: this is the cold-start backfill path, which has no "try again in 5
        // minutes for free" safety net the checkpoint loop has, so skipping the flush's own wait
        // would remove real time a freshly-registered listener needs to fire its first callback.
        val state = MutableStateFlow(SensorState(lastSensorEventTimeMs = 0L))
        var flushCalled = false
        var reRegisterCalled = false
        var clock = 200L

        val result = confirmFreshSensorReadingWithFallback(
            sensorState = state,
            doFlush = { flushCalled = true; true },
            doReRegister = { reRegisterCalled = true },
            label = "test",
            now = { clock.also { clock += 1 } }
        )

        assertFalse(result)
        assertTrue("Flush should still be attempted even though the sensor has never reported", flushCalled)
        assertFalse("Sensor never reported; re-register should not have been attempted", reRegisterCalled)
    }
}
