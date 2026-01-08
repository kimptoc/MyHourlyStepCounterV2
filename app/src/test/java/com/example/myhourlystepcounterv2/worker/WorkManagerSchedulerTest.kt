package com.example.myhourlystepcounterv2.worker

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkManagerSchedulerTest {
    @Test
    fun testStepCounterIntervalIs15Minutes() {
        assertEquals(
            "STEP_COUNTER_INTERVAL_MINUTES should be 15 (minutes)",
            15,
            WorkManagerScheduler.STEP_COUNTER_INTERVAL_MINUTES
        )
    }
}
