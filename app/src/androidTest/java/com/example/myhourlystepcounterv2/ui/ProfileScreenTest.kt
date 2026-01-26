package com.example.myhourlystepcounterv2.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.myhourlystepcounterv2.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProfileScreenTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun testBatteryOptimizationWarning() {
        // Navigate to the Profile screen
        composeTestRule.onNodeWithText("Profile").performClick()

        // Note: This test relies on the actual device state for battery optimization.
        // To test both states, you would need to manually enable/disable battery optimization
        // on the test device before running the test.

        // We can check for either the warning or the success message.
        try {
            composeTestRule.onNodeWithText("Battery Optimization Active").assertIsDisplayed()
            composeTestRule.onNodeWithText("Fix Battery Optimization").assertIsDisplayed()
        } catch (e: AssertionError) {
            composeTestRule.onNodeWithText("Battery Optimization Disabled").assertIsDisplayed()
        }
    }
}

