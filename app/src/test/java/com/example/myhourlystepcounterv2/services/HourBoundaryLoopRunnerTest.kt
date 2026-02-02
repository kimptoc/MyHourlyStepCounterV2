package com.example.myhourlystepcounterv2.services

import java.util.Calendar
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HourBoundaryLoopRunnerTest {
    private class FakeDelayProvider : HourBoundaryDelayProvider {
        var delayCalls: Int = 0
        val delays = mutableListOf<Long>()

        override suspend fun delay(ms: Long) {
            delayCalls++
            delays.add(ms)
        }
    }

    private class FixedTimeProvider(private val nowMillis: Long) : HourBoundaryTimeProvider {
        override fun now(): Calendar = Calendar.getInstance().apply { timeInMillis = nowMillis }

        override fun nextHour(from: Calendar): Calendar {
            return Calendar.getInstance().apply {
                timeInMillis = from.timeInMillis + 60 * 60 * 1000
            }
        }
    }

    @Test
    fun innerLoop_survivesIterationFailure_andContinues() = runTest {
        val delayProvider = FakeDelayProvider()
        val timeProvider = FixedTimeProvider(0L)
        val runner = HourBoundaryLoopRunner(timeProvider, delayProvider)

        var active = true
        var handleCalls = 0
        var failureCallbackCount = 0
        var successCallbackCount = 0

        runner.runInnerLoop(
            isActive = { active },
            setActive = { active = it },
            checkMissed = { },
            handleBoundary = {
                handleCalls++
                throw RuntimeException("boom")
            },
            onIterationSuccess = { successCallbackCount++ },
            onIterationFailure = { _, _ ->
                failureCallbackCount++
                active = false
            }
        )

        assertEquals(1, handleCalls)
        assertEquals(1, failureCallbackCount)
        assertEquals(0, successCallbackCount)
        assertEquals(2, delayProvider.delayCalls) // initial delay + backoff delay
        assertFalse(active)
    }

    @Test
    fun innerLoop_reportsSuccess_andStopsWhenActiveFalse() = runTest {
        val delayProvider = FakeDelayProvider()
        val timeProvider = FixedTimeProvider(0L)
        val runner = HourBoundaryLoopRunner(timeProvider, delayProvider)

        var active = true
        var handleCalls = 0
        var successCallbackCount = 0

        runner.runInnerLoop(
            isActive = { active },
            setActive = { active = it },
            checkMissed = { },
            handleBoundary = { handleCalls++ },
            onIterationSuccess = {
                successCallbackCount++
                active = false
            }
        )

        assertEquals(1, handleCalls)
        assertEquals(1, successCallbackCount)
        assertEquals(1, delayProvider.delayCalls)
        assertFalse(active)
    }

    @Test
    fun runWithRecovery_restartsAfterFailure_thenSucceeds() = runTest {
        val delayProvider = FakeDelayProvider()
        val runner = HourBoundaryLoopRunner(delayProvider = delayProvider)

        var startCalls = 0
        var restartCalls = 0
        var giveUpCalled = false

        runner.runWithRecovery(
            maxRestarts = 3,
            startLoop = {
                startCalls++
                if (startCalls == 1) throw RuntimeException("fail once")
            },
            onRestart = { _, _ -> restartCalls++ },
            onGiveUp = { giveUpCalled = true }
        )

        assertEquals(2, startCalls)
        assertEquals(1, restartCalls)
        assertFalse(giveUpCalled)
        assertEquals(1, delayProvider.delayCalls)
    }

    @Test
    fun runWithRecovery_givesUpAfterMaxRestarts() = runTest {
        val delayProvider = FakeDelayProvider()
        val runner = HourBoundaryLoopRunner(delayProvider = delayProvider)

        var startCalls = 0
        var restartCalls = 0
        var giveUpCalled = false

        runner.runWithRecovery(
            maxRestarts = 3,
            startLoop = {
                startCalls++
                throw RuntimeException("always fails")
            },
            onRestart = { _, _ -> restartCalls++ },
            onGiveUp = { giveUpCalled = true }
        )

        assertEquals(3, startCalls)
        assertEquals(3, restartCalls)
        assertTrue(giveUpCalled)
        assertEquals(2, delayProvider.delayCalls)
    }
}
