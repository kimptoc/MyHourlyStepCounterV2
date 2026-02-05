package com.example.myhourlystepcounterv2.services

import org.junit.Assert.assertEquals
import org.junit.Test

class StepCounterForegroundServiceTest {
    @Test
    fun resolvePreviousHourTimestamp_correctsStaleTimestamp() {
        val currentHour = 1_700_000_000_000L
        val stale = currentHour - (2 * 60 * 60 * 1000)
        val expected = currentHour - (60 * 60 * 1000)

        val resolved = StepCounterForegroundService.resolvePreviousHourTimestamp(
            currentHourTimestamp = currentHour,
            savedHourTimestamp = stale
        )

        assertEquals(expected, resolved)
    }
}
