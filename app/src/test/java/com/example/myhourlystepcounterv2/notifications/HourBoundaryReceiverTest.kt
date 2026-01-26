package com.example.myhourlystepcounterv2.notifications

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Simplified smoke tests for HourBoundaryReceiver.
 *
 * These tests verify basic behavior without testing complex integration scenarios.
 * Business logic (step calculation, validation) is tested in HourBoundaryLogicTest.
 * Full integration testing is covered by instrumentation tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class HourBoundaryReceiverTest {

    private lateinit var receiver: HourBoundaryReceiver
    private lateinit var context: Context

    @OptIn(ExperimentalCoroutinesApi::class)
    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        receiver = HourBoundaryReceiver()
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        // Give background coroutines time to finish to prevent leakage
        Thread.sleep(100)
    }

    @Test
    fun testOnReceive_wrongAction_ignores() {
        // Given
        val intent = Intent().apply {
            action = "com.example.myhourlystepcounterv2.WRONG_ACTION"
        }

        // When/Then - should not crash
        receiver.onReceive(context, intent)
    }
}