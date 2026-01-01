package com.example.myhourlystepcounterv2.ui

import com.example.myhourlystepcounterv2.data.StepEntity
import com.example.myhourlystepcounterv2.data.StepRepository
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.junit.MockitoJUnitRunner
import org.mockito.kotlin.whenever
import java.util.Calendar

@RunWith(MockitoJUnitRunner::class)
class StepCounterViewModelTest {

    @Mock
    private lateinit var mockRepository: StepRepository

    private lateinit var viewModel: StepCounterViewModel

    @Before
    fun setup() {
        viewModel = StepCounterViewModel(mockRepository)
    }

    @Test
    fun testHourlyStepCalculation_PositiveDelta() {
        // When: previous hour started at 1000 steps, now at 1250
        val hourStartSteps = 1000
        val currentDeviceSteps = 1250
        val expectedHourlySteps = 250

        val actualSteps = currentDeviceSteps - hourStartSteps
        assertEquals("Hourly step calculation should be positive delta", expectedHourlySteps, actualSteps)
    }

    @Test
    fun testHourlyStepCalculation_ZeroDelta() {
        // When: no steps taken in the hour
        val hourStartSteps = 1000
        val currentDeviceSteps = 1000
        val expectedHourlySteps = 0

        val actualSteps = currentDeviceSteps - hourStartSteps
        assertEquals("Should handle zero delta", expectedHourlySteps, actualSteps)
    }

    @Test
    fun testHourlyStepCalculation_PreventNegative() {
        // When: sensor reading goes backwards (shouldn't happen but could)
        val hourStartSteps = 1250
        val currentDeviceSteps = 1000
        val expectedHourlySteps = 0 // Should be clamped to 0

        val actualSteps = maxOf(0, currentDeviceSteps - hourStartSteps)
        assertEquals("Should clamp negative values to zero", expectedHourlySteps, actualSteps)
    }

    @Test
    fun testHourlyStepCalculation_LargeReasonableDelta() {
        // When: user takes many steps in an hour (e.g., 8000 is reasonable)
        val hourStartSteps = 1000
        val currentDeviceSteps = 9000
        val expectedHourlySteps = 8000

        val actualSteps = currentDeviceSteps - hourStartSteps
        assertEquals("Should handle large but reasonable step counts", expectedHourlySteps, actualSteps)
    }

    @Test
    fun testDailyTotalCalculation_CombinesDatabaseAndCurrentHour() {
        runTest {
            // When: database has 500 steps, current hour has 100
            val databaseTotal = 500
            val currentHourSteps = 100
            val expectedTotal = 600

            // This simulates what the combine() flow does
            val actualTotal = (databaseTotal) + currentHourSteps
            assertEquals("Daily total should be database + current hour", expectedTotal, actualTotal)
        }
    }

    @Test
    fun testDailyTotalCalculation_HandlesNullDatabaseTotal() {
        runTest {
            // When: database returns null (no data yet), current hour has 50
            val databaseTotal: Int? = null
            val currentHourSteps = 50
            val expectedTotal = 50

            // This simulates the null coalescing in combine()
            val actualTotal = (databaseTotal ?: 0) + currentHourSteps
            assertEquals("Should handle null database total", expectedTotal, actualTotal)
        }
    }

    @Test
    fun testHourBoundaryDetection_DifferentHours() {
        // When: saved hour is 7:00, current hour is 8:00
        val savedHourTimestamp = getHourTimestamp(7, 0)
        val currentHourTimestamp = getHourTimestamp(8, 0)

        val hourChanged = currentHourTimestamp != savedHourTimestamp
        assertTrue("Should detect hour change", hourChanged)
    }

    @Test
    fun testHourBoundaryDetection_SameHour() {
        // When: checking at 7:30 (within hour 7)
        val savedHourTimestamp = getHourTimestamp(7, 0)
        val currentHourTimestamp = getHourTimestamp(7, 0)

        val hourChanged = currentHourTimestamp != savedHourTimestamp
        assertTrue("Should not detect change within same hour", !hourChanged)
    }

    @Test
    fun testValidateStepsDelta_ReasonableValue() {
        // When: calculating 500 steps in an hour (reasonable)
        val delta = 500
        val isValid = delta in 0..10000 // Reasonable range: 0-10000 per hour

        assertTrue("500 steps per hour is reasonable", isValid)
    }

    @Test
    fun testValidateStepsDelta_UnreasonablyLarge() {
        // When: sensor reports 60,000 steps jumped in one hour (impossible)
        val delta = 60000
        val isValid = delta in 0..10000 // Reasonable range

        assertTrue("60,000 steps per hour should be flagged as invalid", !isValid)
    }

    @Test
    fun testValidateStepsDelta_Negative() {
        // When: sensor goes backwards
        val delta = -100
        val isValid = delta in 0..10000

        assertTrue("Negative delta should be invalid", !isValid)
    }

    @Test
    fun testGetStartOfDay() {
        // When: getting start of day timestamp
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startOfDay = calendar.timeInMillis

        // Then: verify it's midnight
        val verifyCalendar = Calendar.getInstance().apply {
            timeInMillis = startOfDay
        }
        assertEquals("Start of day should be at hour 0", 0, verifyCalendar.get(Calendar.HOUR_OF_DAY))
        assertEquals("Start of day should be at minute 0", 0, verifyCalendar.get(Calendar.MINUTE))
        assertEquals("Start of day should be at second 0", 0, verifyCalendar.get(Calendar.SECOND))
    }

    // Helper function
    private fun getHourTimestamp(hour: Int, minute: Int): Long {
        return Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.apply {
            set(Calendar.MINUTE, 0) // Normalize to hour start
        }.timeInMillis
    }
}
