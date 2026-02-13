package com.example.myhourlystepcounterv2.notifications

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StepReminderSyncSuppressionTest {

    @Test
    fun shouldSuppressDueToSync_true_whenNoSteps() {
        assertTrue(
            StepReminderReceiver.shouldSuppressDueToSync(
                currentHourSteps = 0
            )
        )
    }

    @Test
    fun shouldSuppressDueToSync_false_whenStepsAlreadyNonZero() {
        assertFalse(
            StepReminderReceiver.shouldSuppressDueToSync(
                currentHourSteps = 12
            )
        )
    }

    @Test
    fun shouldSuppressDueToSync_false_evenIfNoRecentSensorEvent() {
        // Even with no recent sensor callback, we should still send notification if we have cached steps
        assertFalse(
            StepReminderReceiver.shouldSuppressDueToSync(
                currentHourSteps = 100
            )
        )
    }
}
