package com.example.myhourlystepcounterv2.services

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.android.controller.ServiceController

import android.app.AlarmManager
import org.robolectric.Shadows

/**
 * Simplified smoke tests for StepCounterForegroundService.
 *
 * These tests verify basic service lifecycle without testing complex integration scenarios.
 * Business logic (missed boundary detection, step calculations) is tested in separate logic tests.
 * Full integration testing is covered by instrumentation tests.
 */
import androidx.test.rule.ServiceTestRule
import com.example.myhourlystepcounterv2.notifications.AlarmScheduler
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.verify
import org.junit.Rule

@ExperimentalCoroutinesApi
@RunWith(AndroidJUnit4::class)
class StepCounterForegroundServiceTest {

    @get:Rule
    val serviceRule = ServiceTestRule()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun testOnCreate_schedulesBoundaryCheckAlarm() {
        // Given
        mockkStatic(AlarmScheduler::class)
        every { AlarmScheduler.scheduleBoundaryCheckAlarm(any()) } returns Unit

        // When
        serviceRule.startService(Intent(context, StepCounterForegroundService::class.java))

        // Then
        verify { AlarmScheduler.scheduleBoundaryCheckAlarm(any()) }
    }
}