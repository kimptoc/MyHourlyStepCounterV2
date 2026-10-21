package com.example.myhourlystepcounterv2.ui

import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.example.myhourlystepcounterv2.data.StepPreferences
import com.example.myhourlystepcounterv2.ui.theme.MyHourlyStepCounterV2Theme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Issue #42: the Reminder Timing controls on the Profile screen. Runs under Robolectric rather
 * than as an instrumented test so it runs in CI, and so it never touches the real app's saved
 * data on a connected phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ReminderTimingControlsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var preferences: StepPreferences

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        preferences = StepPreferences(context)
        runBlocking { preferences.clearAll() }
    }

    private fun setProfileContent() {
        composeTestRule.setContent {
            MyHourlyStepCounterV2Theme { ProfileScreen() }
        }
    }

    private fun node(tag: String): SemanticsNodeInteraction =
        composeTestRule.onNodeWithTag(tag, useUnmergedTree = true).performScrollTo()

    /** DataStore writes and reads land off the UI thread, so wait for the UI to catch up. */
    private fun waitForSummary(first: Int, second: Int) {
        val expected = "Reminders fire at XX:%02d and XX:%02d".format(60 - first, 60 - second)
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodes(
                hasTestTag(ReminderTestTags.SUMMARY) and hasText(expected, substring = true),
                useUnmergedTree = true
            ).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun savedOffsets(): Pair<Int, Int> = runBlocking {
        preferences.firstReminderMinutesBefore.first() to preferences.secondReminderMinutesBefore.first()
    }

    private fun enterAndSave(first: String, second: String) {
        node(ReminderTestTags.FIRST_INPUT).performTextReplacement(first)
        node(ReminderTestTags.SECOND_INPUT).performTextReplacement(second)
        node(ReminderTestTags.SAVE).performClick()
        composeTestRule.waitForIdle()
    }

    private fun assertRejected(first: String, second: String) {
        setProfileContent()
        waitForSummary(10, 5)

        enterAndSave(first, second)

        node(ReminderTestTags.ERROR).assertTextContains("First warning must be", substring = true)
        assertEquals("Invalid input must not be saved", 10 to 5, savedOffsets())
        node(ReminderTestTags.SUMMARY).assertTextContains("XX:50 and XX:55", substring = true)
    }

    @Test
    fun showsDefaultOffsets_whenNothingSaved() {
        setProfileContent()
        waitForSummary(10, 5)

        node(ReminderTestTags.FIRST_INPUT).assertTextContains("10")
        node(ReminderTestTags.SECOND_INPUT).assertTextContains("5")
    }

    @Test
    fun showsSavedOffsets_whenAlreadyConfigured() {
        runBlocking { preferences.saveReminderMinutesBefore(12, 6) }
        setProfileContent()
        waitForSummary(12, 6)

        node(ReminderTestTags.FIRST_INPUT).assertTextContains("12")
        node(ReminderTestTags.SECOND_INPUT).assertTextContains("6")
    }

    @Test
    fun validInput_isSaved_andSummaryUpdates() {
        setProfileContent()
        waitForSummary(10, 5)

        enterAndSave("15", "3")

        waitForSummary(15, 3)
        assertEquals(15 to 3, savedOffsets())
        composeTestRule.onNodeWithTag(ReminderTestTags.ERROR, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun rejects_finalAlertNotCloserToTheHour() = assertRejected("10", "10")

    @Test
    fun rejects_finalAlertFurtherFromTheHour() = assertRejected("5", "10")

    @Test
    fun rejects_nonNumericInput() = assertRejected("ten", "5")

    @Test
    fun rejects_outOfRangeInput() = assertRejected("60", "5")

    @Test
    fun rejects_zeroFinalAlert() = assertRejected("10", "0")

    @Test
    fun rejects_emptyInput() = assertRejected("10", "")

    @Test
    fun editingAfterAnError_clearsTheError() {
        setProfileContent()
        waitForSummary(10, 5)
        enterAndSave("5", "10")
        node(ReminderTestTags.ERROR).assertTextContains("First warning must be", substring = true)

        node(ReminderTestTags.SECOND_INPUT).performTextReplacement("4")

        composeTestRule.onNodeWithTag(ReminderTestTags.ERROR, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun savedOffsets_surviveLeavingAndReturningToProfile() {
        var showProfile by mutableStateOf(true)
        composeTestRule.setContent {
            MyHourlyStepCounterV2Theme {
                if (showProfile) ProfileScreen() else Text("Home")
            }
        }
        waitForSummary(10, 5)
        enterAndSave("20", "8")
        waitForSummary(20, 8)

        showProfile = false
        composeTestRule.waitForIdle()
        showProfile = true
        composeTestRule.waitForIdle()

        // A fresh composition has no remembered field state, so these values come from storage.
        waitForSummary(20, 8)
        node(ReminderTestTags.FIRST_INPUT).assertTextContains("20")
        node(ReminderTestTags.SECOND_INPUT).assertTextContains("8")
    }
}
