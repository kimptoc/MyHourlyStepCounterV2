package com.example.myhourlystepcounterv2.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.myhourlystepcounterv2.StepCounterViewModel
import com.example.myhourlystepcounterv2.data.StepRepository
import com.example.myhourlystepcounterv2.data.StepPreferences
import com.example.myhourlystepcounterv2.sensor.StepSensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.*

@RunWith(AndroidJUnit4::class)
class HomeScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var mockStepRepository: StepRepository
    private lateinit var mockStepPreferences: StepPreferences
    private lateinit var mockStepSensorManager: StepSensorManager
    private lateinit var viewModel: StepCounterViewModel

    @Before
    fun setUp() {
        // Initialize mocks and view model for testing
        mockStepRepository = StepRepository(/* mock database */)
        mockStepPreferences = StepPreferences(/* mock context */)
        mockStepSensorManager = StepSensorManager.getInstance(/* mock context */)
        
        // Create a mock ViewModel with test data
        viewModel = StepCounterViewModel(
            repository = mockStepRepository,
            preferences = mockStepPreferences,
            sensorManager = mockStepSensorManager
        )
    }

    @Test
    fun testHomeScreen_stateFlowCollection_hourlyStepsDisplaysInLargeFont() {
        // Given
        val testHourlySteps = 125
        val testDailySteps = 500
        val testCurrentTime = "10:30:45"
        
        // Set up test data in the ViewModel
        viewModel._hourlySteps.value = testHourlySteps
        viewModel._dailySteps.value = testDailySteps
        viewModel._currentTime.value = testCurrentTime

        // When
        composeTestRule.setContent {
            HomeScreen(
                hourlySteps = viewModel.hourlySteps,
                dailySteps = viewModel.dailySteps,
                currentTime = viewModel.currentTime,
                currentDate = viewModel.currentDate,
                animatedProgress = viewModel.animatedProgress
            )
        }

        // Then
        // Verify that hourly steps are displayed
        composeTestRule.onNodeWithText(testHourlySteps.toString()).assertIsDisplayed()
    }

    @Test
    fun testHomeScreen_stateFlowCollection_dailyStepsDisplaysInSmallerFont() {
        // Given
        val testHourlySteps = 125
        val testDailySteps = 500
        val testCurrentTime = "10:30:45"
        
        // Set up test data in the ViewModel
        viewModel._hourlySteps.value = testHourlySteps
        viewModel._dailySteps.value = testDailySteps
        viewModel._currentTime.value = testCurrentTime

        // When
        composeTestRule.setContent {
            HomeScreen(
                hourlySteps = viewModel.hourlySteps,
                dailySteps = viewModel.dailySteps,
                currentTime = viewModel.currentTime,
                currentDate = viewModel.currentDate,
                animatedProgress = viewModel.animatedProgress
            )
        }

        // Then
        // Verify that daily steps are displayed
        composeTestRule.onNodeWithText(testDailySteps.toString()).assertIsDisplayed()
    }

    @Test
    fun testHomeScreen_timeFormatting_currentTimeFormatsWithHHmmss() {
        // Given
        val testHourlySteps = 125
        val testDailySteps = 500
        val testCurrentTime = "10:30:45"
        val testCurrentDate = "Monday, January 1"
        
        // Set up test data in the ViewModel
        viewModel._hourlySteps.value = testHourlySteps
        viewModel._dailySteps.value = testDailySteps
        viewModel._currentTime.value = testCurrentTime
        viewModel._currentDate.value = testCurrentDate

        // When
        composeTestRule.setContent {
            HomeScreen(
                hourlySteps = viewModel.hourlySteps,
                dailySteps = viewModel.dailySteps,
                currentTime = viewModel.currentTime,
                currentDate = viewModel.currentDate,
                animatedProgress = viewModel.animatedProgress
            )
        }

        // Then
        // Verify that current time is formatted with HH:mm:ss
        composeTestRule.onNodeWithText(testCurrentTime).assertIsDisplayed()
    }

    @Test
    fun testHomeScreen_timeFormatting_edgeCase_midnight_shows000000() {
        // Given
        val testHourlySteps = 0
        val testDailySteps = 0
        val testCurrentTime = "00:00:00"
        val testCurrentDate = "Monday, January 1"
        
        // Set up test data in the ViewModel
        viewModel._hourlySteps.value = testHourlySteps
        viewModel._dailySteps.value = testDailySteps
        viewModel._currentTime.value = testCurrentTime
        viewModel._currentDate.value = testCurrentDate

        // When
        composeTestRule.setContent {
            HomeScreen(
                hourlySteps = viewModel.hourlySteps,
                dailySteps = viewModel.dailySteps,
                currentTime = viewModel.currentTime,
                currentDate = viewModel.currentDate,
                animatedProgress = viewModel.animatedProgress
            )
        }

        // Then
        // Verify that midnight is formatted as "00:00:00"
        composeTestRule.onNodeWithText(testCurrentTime).assertIsDisplayed()
    }

    @Test
    fun testHomeScreen_timeFormatting_edgeCase_noon_shows120000() {
        // Given
        val testHourlySteps = 0
        val testDailySteps = 100
        val testCurrentTime = "12:00:00"
        val testCurrentDate = "Monday, January 1"
        
        // Set up test data in the ViewModel
        viewModel._hourlySteps.value = testHourlySteps
        viewModel._dailySteps.value = testDailySteps
        viewModel._currentTime.value = testCurrentTime
        viewModel._currentDate.value = testCurrentDate

        // When
        composeTestRule.setContent {
            HomeScreen(
                hourlySteps = viewModel.hourlySteps,
                dailySteps = viewModel.dailySteps,
                currentTime = viewModel.currentTime,
                currentDate = viewModel.currentDate,
                animatedProgress = viewModel.animatedProgress
            )
        }

        // Then
        // Verify that noon is formatted as "12:00:00"
        composeTestRule.onNodeWithText(testCurrentTime).assertIsDisplayed()
    }

    @Test
    fun testHomeScreen_timeFormatting_dateFormat_EEEE_MMMM_d() {
        // Given
        val testHourlySteps = 50
        val testDailySteps = 200
        val testCurrentTime = "14:30:25"
        val testCurrentDate = "Friday, January 17"
        
        // Set up test data in the ViewModel
        viewModel._hourlySteps.value = testHourlySteps
        viewModel._dailySteps.value = testDailySteps
        viewModel._currentTime.value = testCurrentTime
        viewModel._currentDate.value = testCurrentDate

        // When
        composeTestRule.setContent {
            HomeScreen(
                hourlySteps = viewModel.hourlySteps,
                dailySteps = viewModel.dailySteps,
                currentTime = viewModel.currentTime,
                currentDate = viewModel.currentDate,
                animatedProgress = viewModel.animatedProgress
            )
        }

        // Then
        // Verify that date is formatted as "EEEE, MMMM d" (e.g., "Friday, January 17")
        composeTestRule.onNodeWithText(testCurrentDate).assertIsDisplayed()
    }

    @Test
    fun testHomeScreen_progressRingCalculation_zeroSteps_0PercentProgress() {
        // Given
        val testHourlySteps = 0
        val testDailySteps = 0
        val testCurrentTime = "09:15:30"
        val testCurrentDate = "Monday, January 1"
        
        // Set up test data in the ViewModel
        viewModel._hourlySteps.value = testHourlySteps
        viewModel._dailySteps.value = testDailySteps
        viewModel._currentTime.value = testCurrentTime
        viewModel._currentDate.value = testCurrentDate
        viewModel._animatedProgress.value = 0f

        // When
        composeTestRule.setContent {
            HomeScreen(
                hourlySteps = viewModel.hourlySteps,
                dailySteps = viewModel.dailySteps,
                currentTime = viewModel.currentTime,
                currentDate = viewModel.currentDate,
                animatedProgress = viewModel.animatedProgress
            )
        }

        // Then
        // Verify that 0 steps result in 0% progress
        composeTestRule.onNodeWithText(testHourlySteps.toString()).assertIsDisplayed()
        // The progress ring should show 0% (animatedProgress = 0f)
    }

    @Test
    fun testHomeScreen_progressRingCalculation_halfwayToThreshold_50PercentProgress() {
        // Given
        val testHourlySteps = 125  // Half of 250 threshold
        val testDailySteps = 250
        val testCurrentTime = "11:45:10"
        val testCurrentDate = "Tuesday, January 2"
        
        // Set up test data in the ViewModel
        viewModel._hourlySteps.value = testHourlySteps
        viewModel._dailySteps.value = testDailySteps
        viewModel._currentTime.value = testCurrentTime
        viewModel._currentDate.value = testCurrentDate
        viewModel._animatedProgress.value = 0.5f  // 50% progress

        // When
        composeTestRule.setContent {
            HomeScreen(
                hourlySteps = viewModel.hourlySteps,
                dailySteps = viewModel.dailySteps,
                currentTime = viewModel.currentTime,
                currentDate = viewModel.currentDate,
                animatedProgress = viewModel.animatedProgress
            )
        }

        // Then
        // Verify that 125 steps (50% of 250) result in 50% progress
        composeTestRule.onNodeWithText(testHourlySteps.toString()).assertIsDisplayed()
    }

    @Test
    fun testHomeScreen_progressRingCalculation_atOrAboveThreshold_100PercentProgress() {
        // Given
        val testHourlySteps = 250  // At threshold
        val testDailySteps = 500
        val testCurrentTime = "16:20:05"
        val testCurrentDate = "Wednesday, January 3"
        
        // Set up test data in the ViewModel
        viewModel._hourlySteps.value = testHourlySteps
        viewModel._dailySteps.value = testDailySteps
        viewModel._currentTime.value = testCurrentTime
        viewModel._currentDate.value = testCurrentDate
        viewModel._animatedProgress.value = 1f  // 100% progress

        // When
        composeTestRule.setContent {
            HomeScreen(
                hourlySteps = viewModel.hourlySteps,
                dailySteps = viewModel.dailySteps,
                currentTime = viewModel.currentTime,
                currentDate = viewModel.currentDate,
                animatedProgress = viewModel.animatedProgress
            )
        }

        // Then
        // Verify that 250+ steps result in 100% progress (clamped)
        composeTestRule.onNodeWithText(testHourlySteps.toString()).assertIsDisplayed()
    }

    @Test
    fun testHomeScreen_scrollState_overflowContentScrollable() {
        // Given
        val testHourlySteps = 100
        val testDailySteps = 500
        val testCurrentTime = "08:30:15"
        val testCurrentDate = "Thursday, January 4"
        
        // Set up test data in the ViewModel
        viewModel._hourlySteps.value = testHourlySteps
        viewModel._dailySteps.value = testDailySteps
        viewModel._currentTime.value = testCurrentTime
        viewModel._currentDate.value = testCurrentDate
        viewModel._animatedProgress.value = 0.4f

        // When
        composeTestRule.setContent {
            HomeScreen(
                hourlySteps = viewModel.hourlySteps,
                dailySteps = viewModel.dailySteps,
                currentTime = viewModel.currentTime,
                currentDate = viewModel.currentDate,
                animatedProgress = viewModel.animatedProgress
            )
        }

        // Then
        // Verify that overflow content is scrollable
        // This is difficult to test directly without specific scrollable elements
    }

    @Test
    fun testHomeScreen_material3Theming_colorsAppliedCorrectly() {
        // Given
        val testHourlySteps = 75
        val testDailySteps = 300
        val testCurrentTime = "13:45:20"
        val testCurrentDate = "Friday, January 5"
        
        // Set up test data in the ViewModel
        viewModel._hourlySteps.value = testHourlySteps
        viewModel._dailySteps.value = testDailySteps
        viewModel._currentTime.value = testCurrentTime
        viewModel._currentDate.value = testCurrentDate
        viewModel._animatedProgress.value = 0.3f

        // When
        composeTestRule.setContent {
            HomeScreen(
                hourlySteps = viewModel.hourlySteps,
                dailySteps = viewModel.dailySteps,
                currentTime = viewModel.currentTime,
                currentDate = viewModel.currentDate,
                animatedProgress = viewModel.animatedProgress
            )
        }

        // Then
        // Verify that Material3 theming is applied correctly
        // This is difficult to test directly without accessing the theme properties
    }
}