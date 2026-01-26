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

/**
 * Simplified smoke tests for StepCounterForegroundService.
 *
 * These tests verify basic service lifecycle without testing complex integration scenarios.
 * Business logic (missed boundary detection, step calculations) is tested in separate logic tests.
 * Full integration testing is covered by instrumentation tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class StepCounterForegroundServiceTest {

    private lateinit var serviceController: ServiceController<StepCounterForegroundService>
    private lateinit var service: StepCounterForegroundService
    private lateinit var context: Context

    @OptIn(ExperimentalCoroutinesApi::class)
    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()

        // Use Robolectric's ServiceController to properly initialize the service
        serviceController = Robolectric.buildService(StepCounterForegroundService::class.java)
        service = serviceController.create().get()

        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        // Clean up service and give background coroutines time to finish
        try {
            service.onDestroy()
        } catch (e: Exception) {
            // Ignore cleanup errors
        }
        Thread.sleep(100)
    }

    @Test
    fun testOnStartCommand_returnsStartSticky() {
        // Given
        val intent = Intent(context, StepCounterForegroundService::class.java)
        val flags = 0
        val startId = 1

        // When
        val result = service.onStartCommand(intent, flags, startId)

        // Then - verify service returns START_STICKY for restart behavior
        assert(result == android.app.Service.START_STICKY)
    }
}