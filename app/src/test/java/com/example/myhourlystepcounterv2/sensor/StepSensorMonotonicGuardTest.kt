package com.example.myhourlystepcounterv2.sensor

import org.junit.Assert.assertEquals
import org.junit.Test

class StepSensorMonotonicGuardTest {

    @Test
    fun clampMonotonicHourSteps_keepsPreviousWhenCalculatedDrops() {
        val result = clampMonotonicHourSteps(
            previousDisplayed = 19,
            calculatedStepsThisHour = 13
        )
        assertEquals(19, result)
    }

    @Test
    fun clampMonotonicHourSteps_usesCalculatedWhenItIncreases() {
        val result = clampMonotonicHourSteps(
            previousDisplayed = 19,
            calculatedStepsThisHour = 25
        )
        assertEquals(25, result)
    }

    @Test
    fun clampMonotonicHourSteps_neverReturnsNegative() {
        val result = clampMonotonicHourSteps(
            previousDisplayed = 0,
            calculatedStepsThisHour = -50
        )
        assertEquals(0, result)
    }

    @Test
    fun clampMonotonicHourSteps_allowsHourBoundaryReset_whenPreviousAlreadyZeroed() {
        // Hour reset path sets currentHourSteps to 0 before sensor callbacks resume.
        val result = clampMonotonicHourSteps(
            previousDisplayed = 0,
            calculatedStepsThisHour = 0
        )
        assertEquals(0, result)
    }
}
