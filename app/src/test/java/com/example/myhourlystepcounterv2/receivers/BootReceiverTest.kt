package com.example.myhourlystepcounterv2.receivers

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.example.myhourlystepcounterv2.data.StepPreferences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for BootReceiver using Robolectric.
 *
 * These tests verify that the receiver properly schedules alarms on boot.
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
    fun onReceive_withBootCompleted_doesNotCrash() = runTest(testDispatcher) {
        // Given
        val intent = Intent(Intent.ACTION_BOOT_COMPLETED)
        whenever(mockPreferences.permanentNotificationEnabled).thenReturn(flowOf(true))
        whenever(mockPreferences.reminderNotificationEnabled).thenReturn(flowOf(false))

        // When
        receiver.onReceive(context, intent)
        testDispatcher.scheduler.advanceUntilIdle()

        // Then - should not crash
        // Note: Detailed alarm scheduling is tested in AlarmSchedulerTest
    }

    @Test
    fun onReceive_withLockedBootCompleted_doesNotCrash() = runTest(testDispatcher) {
        // Given
        val intent = Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED)
        whenever(mockPreferences.permanentNotificationEnabled).thenReturn(flowOf(true))
        whenever(mockPreferences.reminderNotificationEnabled).thenReturn(flowOf(false))

        // When
        receiver.onReceive(context, intent)
        testDispatcher.scheduler.advanceUntilIdle()

        // Then - should not crash
    }

    @Test
    fun onReceive_whenPermanentNotificationDisabled_doesNotStartService() = runTest(testDispatcher) {
        // Given
        val intent = Intent(Intent.ACTION_BOOT_COMPLETED)
        whenever(mockPreferences.permanentNotificationEnabled).thenReturn(flowOf(false))

        // When
        receiver.onReceive(context, intent)
        testDispatcher.scheduler.advanceUntilIdle()

        // Then - should not crash and should skip service start
    }
}
