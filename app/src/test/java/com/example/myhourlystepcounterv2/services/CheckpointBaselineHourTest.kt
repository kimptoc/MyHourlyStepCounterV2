package com.example.myhourlystepcounterv2.services

import com.example.myhourlystepcounterv2.services.StepCounterForegroundService.Companion.isCheckpointBaselineForHour
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A checkpoint whose flush wait straddled XX:00 used to pair the previous hour's baseline with
 * the new wall-clock hour, writing the previous hour's whole delta into the new hour's row.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CheckpointBaselineHourTest {

    private val hour20 = 1_792_609_200_000L
    private val hour21 = hour20 + 3_600_000L

    @Test
    fun allowsCheckpoint_whenBaselineBelongsToCurrentHour() {
        assertTrue(isCheckpointBaselineForHour(baselineHourTimestamp = hour21, currentHourTimestamp = hour21))
    }

    @Test
    fun skipsCheckpoint_whenBoundaryNotYetProcessed() {
        assertFalse(isCheckpointBaselineForHour(baselineHourTimestamp = hour20, currentHourTimestamp = hour21))
    }

    @Test
    fun defersToBaselineCheck_whenNoHourSavedYet() {
        assertTrue(isCheckpointBaselineForHour(baselineHourTimestamp = 0L, currentHourTimestamp = hour21))
    }
}
