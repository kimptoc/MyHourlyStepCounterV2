package com.example.myhourlystepcounterv2.ui

import com.example.myhourlystepcounterv2.data.StepRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class DailyStepsFlowTest {

    @Test
    fun queryIsSubscribedOnceAcrossMultipleStepEmissions() = runTest {
        val lastStartOfDay = MutableStateFlow(1_000L)
        val currentHourTimestamp = MutableStateFlow(2_000L)
        val hourlySteps = MutableStateFlow(0)

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
            currentHourTimestamp = currentHourTimestamp,
            hourlySteps = hourlySteps,
            repository = repository,
            fallbackStartOfDay = { 0L }
        )

        val results = mutableListOf<Int>()
        val job = launch { dailyFlow.toList(results) }

        runCurrent()
        assertEquals("Initial collection should subscribe once", 1, subscriptions)

        hourlySteps.value = 1
        hourlySteps.value = 2
        hourlySteps.value = 3
        runCurrent()

        assertEquals("Step emissions must not re-subscribe the Room query", 1, subscriptions)
        assertEquals("Daily total should add live steps to the persisted total", 103, results.last())

        currentHourTimestamp.value = 3_000L
        runCurrent()
        assertEquals("An hour boundary should re-subscribe the Room query once", 2, subscriptions)

        job.cancelAndJoin()
    }
}
