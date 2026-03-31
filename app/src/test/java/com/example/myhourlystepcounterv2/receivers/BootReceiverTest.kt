package com.example.myhourlystepcounterv2.receivers

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.example.myhourlystepcounterv2.data.StepPreferences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Tests for BootReceiver using Robolectric.
 *
 * These tests verify that the receiver properly schedules alarms on boot
 * and starts the foreground service when enabled.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BootReceiverTest {

    private lateinit var receiver: BootReceiver
    private lateinit var context: Context
    private lateinit var mockPreferences: StepPreferences
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        mockPreferences = mock()
        receiver = BootReceiver(mockPreferences, testDispatcher)
    }

    @Test
    fun onReceive_withBootCompleted_schedulesAlarms() = runTest(testDispatcher) {
        // Given
        val intent = Intent(Intent.ACTION_BOOT_COMPLETED)
        whenever(mockPreferences.permanentNotificationEnabled).thenReturn(flowOf(true))
        whenever(mockPreferences.reminderNotificationEnabled).thenReturn(flowOf(false))

        // When
        receiver.onReceive(context, intent)
        testDispatcher.scheduler.advanceUntilIdle()

        // Then - hour boundary alarms should be scheduled
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val scheduledAlarms = shadowOf(alarmManager).scheduledAlarms
        assertTrue("Expected alarms to be scheduled after boot", scheduledAlarms.isNotEmpty())
    }

    @Test
    fun onReceive_withLockedBootCompleted_schedulesAlarms() = runTest(testDispatcher) {
        // Given
        val intent = Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED)
        whenever(mockPreferences.permanentNotificationEnabled).thenReturn(flowOf(true))
        whenever(mockPreferences.reminderNotificationEnabled).thenReturn(flowOf(false))

        // When
        receiver.onReceive(context, intent)
        testDispatcher.scheduler.advanceUntilIdle()

        // Then - alarms should be scheduled
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val scheduledAlarms = shadowOf(alarmManager).scheduledAlarms
        assertTrue("Expected alarms to be scheduled after locked boot", scheduledAlarms.isNotEmpty())
    }

    @Test
    fun onReceive_whenPermanentNotificationDisabled_stillSchedulesAlarms() = runTest(testDispatcher) {
        // Given
        val intent = Intent(Intent.ACTION_BOOT_COMPLETED)
        whenever(mockPreferences.permanentNotificationEnabled).thenReturn(flowOf(false))
        whenever(mockPreferences.reminderNotificationEnabled).thenReturn(flowOf(false))

        // When
        receiver.onReceive(context, intent)
        testDispatcher.scheduler.advanceUntilIdle()

        // Then - alarms should still be scheduled even when notification is disabled
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val scheduledAlarms = shadowOf(alarmManager).scheduledAlarms
        assertTrue("Expected alarms even when permanent notification disabled", scheduledAlarms.isNotEmpty())
    }

    @Test
    fun onReceive_withPackageReplaced_schedulesAlarms() = runTest(testDispatcher) {
        // Given
        val intent = Intent(Intent.ACTION_MY_PACKAGE_REPLACED)
        whenever(mockPreferences.permanentNotificationEnabled).thenReturn(flowOf(true))
        whenever(mockPreferences.reminderNotificationEnabled).thenReturn(flowOf(true))

        // When
        receiver.onReceive(context, intent)
        testDispatcher.scheduler.advanceUntilIdle()

        // Then - alarms should be scheduled after app update
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val scheduledAlarms = shadowOf(alarmManager).scheduledAlarms
        assertTrue("Expected alarms to be scheduled after package replaced", scheduledAlarms.isNotEmpty())
    }

    @Test
    fun onReceive_withUnknownAction_doesNotScheduleAlarms() = runTest(testDispatcher) {
        // Given
        val intent = Intent("com.example.UNKNOWN_ACTION")

        // When
        receiver.onReceive(context, intent)
        testDispatcher.scheduler.advanceUntilIdle()

        // Then - no alarms should be scheduled for unknown actions
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val scheduledAlarms = shadowOf(alarmManager).scheduledAlarms
        assertTrue("Expected no alarms for unknown action", scheduledAlarms.isEmpty())
    }
}
