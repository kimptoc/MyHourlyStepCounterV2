package com.example.myhourlystepcounterv2.notifications

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StepReminderSyncSuppressionTest {

    @Test
    fun shouldSuppressDueToSync_true_whenNoFreshReadingAndZeroSteps() {
        assertTrue(
            StepReminderReceiver.shouldSuppressDueToSync(
                hasFreshReading = false,
                currentHourSteps = 0
            )
        )
    }

    @Test
    fun shouldSuppressDueToSync_false_whenFreshReadingExists() {
        assertFalse(
            StepReminderReceiver.shouldSuppressDueToSync(
                hasFreshReading = true,
                currentHourSteps = 0
            )
        )
    }

    @Test
    fun shouldSuppressDueToSync_false_whenStepsAlreadyNonZero() {
        assertFalse(
            StepReminderReceiver.shouldSuppressDueToSync(
                hasFreshReading = false,
                currentHourSteps = 12
            )
        )
    }
}
