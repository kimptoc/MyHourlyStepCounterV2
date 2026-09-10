package com.example.myhourlystepcounterv2.ui

import com.example.myhourlystepcounterv2.data.StepRepository
import com.example.myhourlystepcounterv2.wallClockHourTimestamp
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class DailyStepsFlowTest {

    @Test
    fun queryIsSubscribedOncePerWallClockHourAcrossMultipleStepEmissions() = runTest {
        val lastStartOfDay = MutableStateFlow(1_000L)
        val currentTime = MutableStateFlow(0L)
        val hourlySteps = MutableStateFlow(0)
        val hourMs = 60L * 60L * 1000L
        val hourStart = wallClockHourTimestamp(0L)

        var subscriptions = 0
        val repository = mock<StepRepository>()
        whenever(repository.getTotalStepsForDayExcludingCurrentHour(any(), any())).thenReturn(
            flow<Int?> {
                subscriptions++
                emit(100)
            }
        )

        val dailyFlow = dailyStepsFlow(
            lastStartOfDay = lastStartOfDay,
            currentTime = currentTime,
            hourlySteps = hourlySteps,
            repository = repository,
            fallbackStartOfDay = { 0L }
        )

        val results = mutableListOf<Int>()
        val job = launch { dailyFlow.toList(results) }

        advanceUntilIdle()
        assertEquals("Initial collection should subscribe once", 1, subscriptions)

        hourlySteps.value = 1
        hourlySteps.value = 2
        hourlySteps.value = 3
        advanceUntilIdle()

        assertEquals("Step emissions must not re-subscribe the Room query", 1, subscriptions)
        assertEquals("Daily total should add live steps to the persisted total", 103, results.last())

        // Clock ticks within the same wall-clock hour: must not re-subscribe.
        currentTime.value = hourStart + 1_000L
        advanceUntilIdle()
        assertEquals("A same-hour clock tick must not re-subscribe the Room query", 1, subscriptions)

        // Clock crosses into the next wall-clock hour: must re-subscribe exactly once.
        currentTime.value = hourStart + hourMs
        advanceUntilIdle()
        assertEquals("An hour boundary should re-subscribe the Room query once", 2, subscriptions)

        job.cancelAndJoin()
    }

    @Test
    fun exclusionKeyTracksWallClockInsteadOfAStalePreference() = runTest {
        // Issue #27: the service's checkpoint loop writes a partial row keyed by wall-clock
        // time regardless of whether hour-boundary processing has advanced
        // preferences.currentHourTimestamp yet. dailyStepsFlow no longer takes that preference
        // as input at all -- this asserts the exclusion key tracks the clock (currentTime)
        // directly, so a stale preference can no longer leak that partial row into dbTotal
        // while hourlySteps also counts the same steps live (a double count).
        val lastStartOfDay = MutableStateFlow(0L)
        val hourMs = 60L * 60L * 1000L
        val hourOne = wallClockHourTimestamp(0L)
        val hourTwo = hourOne + hourMs
        val currentTime = MutableStateFlow(0L)
        val hourlySteps = MutableStateFlow(0)

        val repository = mock<StepRepository>()
        whenever(repository.getTotalStepsForDayExcludingCurrentHour(any(), eq(hourOne)))
            .thenReturn(flow { emit(100) })
        whenever(repository.getTotalStepsForDayExcludingCurrentHour(any(), eq(hourTwo)))
            .thenReturn(flow { emit(500) })

        val dailyFlow = dailyStepsFlow(
            lastStartOfDay = lastStartOfDay,
            currentTime = currentTime,
            hourlySteps = hourlySteps,
            repository = repository,
            fallbackStartOfDay = { 0L }
        )

        val results = mutableListOf<Int>()
        val job = launch { dailyFlow.toList(results) }
        advanceUntilIdle()
        assertEquals("Should exclude the wall-clock hour derived from currentTime", 100, results.last())

        currentTime.value = hourTwo
        advanceUntilIdle()
        assertEquals(
            "Crossing into the next wall-clock hour must switch the exclusion key, " +
                "with no dependency on any stored preference",
            500,
            results.last()
        )

        job.cancelAndJoin()
    }
}
