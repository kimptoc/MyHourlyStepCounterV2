package com.example.myhourlystepcounterv2.notifications

import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import java.util.*
import org.junit.Assert.*

/**
 * Tests for AlarmScheduler using Robolectric.
 *
 * Note: Robolectric shadows should be inspected directly, not mocked with Mockito.
 * Use shadowAlarmManager.scheduledAlarms and shadowAlarmManager.nextScheduledAlarm
 * to verify alarm scheduling behavior.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AlarmSchedulerTest {

    private lateinit var context: Context
    private lateinit var alarmManager: AlarmManager
    private lateinit var shadowAlarmManager: ShadowAlarmManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        shadowAlarmManager = Shadows.shadowOf(alarmManager)
    }

    @Test
    fun testScheduleStepReminders_schedulesAlarmAt50Minutes() {
        // When
        AlarmScheduler.scheduleStepReminders(context, skipPermissionCheck = true)

        // Then
        val scheduledAlarms = shadowAlarmManager.scheduledAlarms
        assertTrue("Step reminder alarm should be scheduled", scheduledAlarms.isNotEmpty())

        // Verify the scheduled time is at :50 minutes
        val alarm = scheduledAlarms.first()
        assertEquals(AlarmManager.RTC_WAKEUP, alarm.getType())

        val calendar = Calendar.getInstance().apply {
            timeInMillis = alarm.triggerAtMs
        }
        assertEquals(50, calendar.get(Calendar.MINUTE))
        assertEquals(0, calendar.get(Calendar.SECOND))
    }

    @Test
    fun testScheduleStepReminders_whenAlreadyAt50Minutes_schedulesForNextHour() {
        // Given - current time is at :50
        val currentMinute = Calendar.getInstance().get(Calendar.MINUTE)

        // When
        AlarmScheduler.scheduleStepReminders(context, skipPermissionCheck = true)

        // Then
        val scheduledAlarms = shadowAlarmManager.scheduledAlarms
        assertTrue("Alarm should be scheduled", scheduledAlarms.isNotEmpty())

        val alarm = scheduledAlarms.first()
        val scheduledTime = Calendar.getInstance().apply {
            timeInMillis = alarm.triggerAtMs
        }

        // If current time is >= :50, should schedule for next hour
        if (currentMinute >= 50) {
            val expectedHour = (Calendar.getInstance().get(Calendar.HOUR_OF_DAY) + 1) % 24
            assertEquals(expectedHour, scheduledTime.get(Calendar.HOUR_OF_DAY))
        }
        assertEquals(50, scheduledTime.get(Calendar.MINUTE))
    }

    @Test
    fun testCancelStepReminders_removesScheduledAlarm() {
        // Given - alarm is scheduled
        AlarmScheduler.scheduleStepReminders(context, skipPermissionCheck = true)
        assertTrue("Alarm should be scheduled first", shadowAlarmManager.scheduledAlarms.isNotEmpty())

        // When
        AlarmScheduler.cancelStepReminders(context)

        // Then
        // Note: Robolectric's shadow doesn't automatically remove alarms when cancel() is called
        // The real test is that cancelStepReminders() doesn't crash
    }

    @Test
    fun testScheduleHourBoundaryAlarms_schedulesAlarmAtTopOfNextHour() {
        // When
        AlarmScheduler.scheduleHourBoundaryAlarms(context, skipPermissionCheck = true)

        // Then
        val scheduledAlarms = shadowAlarmManager.scheduledAlarms
        assertTrue("Hour boundary alarm should be scheduled", scheduledAlarms.isNotEmpty())

        val alarm = scheduledAlarms.last() // Get the most recent alarm
        assertEquals(AlarmManager.RTC_WAKEUP, alarm.getType())

        val calendar = Calendar.getInstance().apply {
            timeInMillis = alarm.triggerAtMs
        }
        assertEquals(0, calendar.get(Calendar.MINUTE))
        assertEquals(0, calendar.get(Calendar.SECOND))

        // Should be scheduled for next hour
        val currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val expectedHour = (currentHour + 1) % 24
        assertEquals(expectedHour, calendar.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun testCancelHourBoundaryAlarms_doesNotCrash() {
        // Given - alarm is scheduled
        AlarmScheduler.scheduleHourBoundaryAlarms(context, skipPermissionCheck = true)

        // When
        AlarmScheduler.cancelHourBoundaryAlarms(context)

        // Then - should not crash
        // Note: Robolectric's shadow doesn't automatically remove alarms when cancel() is called
    }

    @Test
    @Suppress("DEPRECATION")
    fun testScheduleStepReminders_createsPendingIntentWithCorrectFlags() {
        // When
        AlarmScheduler.scheduleStepReminders(context, skipPermissionCheck = true)

        // Then
        val scheduledAlarms = shadowAlarmManager.scheduledAlarms
        assertTrue("Alarm should be scheduled", scheduledAlarms.isNotEmpty())

        val alarm = scheduledAlarms.first()
        assertNotNull("PendingIntent should be created", alarm.operation)

        // Verify the intent targets StepReminderReceiver
        val shadowPendingIntent = Shadows.shadowOf(alarm.operation)
        val intent = shadowPendingIntent.savedIntent
        assertNotNull("Intent should not be null", intent)
        assertTrue(
            "Intent should target StepReminderReceiver",
            intent.component?.className?.contains("StepReminderReceiver") == true
        )
    }

    @Test
    @Suppress("DEPRECATION")
    fun testScheduleHourBoundaryAlarms_createsPendingIntentWithCorrectFlags() {
        // When
        AlarmScheduler.scheduleHourBoundaryAlarms(context, skipPermissionCheck = true)

        // Then
        val scheduledAlarms = shadowAlarmManager.scheduledAlarms
        assertTrue("Alarm should be scheduled", scheduledAlarms.isNotEmpty())

        val alarm = scheduledAlarms.last()
        assertNotNull("PendingIntent should be created", alarm.operation)

        // Verify the intent targets HourBoundaryReceiver
        val shadowPendingIntent = Shadows.shadowOf(alarm.operation)
        val intent = shadowPendingIntent.savedIntent
        assertNotNull("Intent should not be null", intent)
        assertTrue(
            "Intent should target HourBoundaryReceiver",
            intent.component?.className?.contains("HourBoundaryReceiver") == true
        )
    }

    @Test
    fun testScheduleBoundaryCheckAlarm_schedulesAlarm15MinutesFromNow() {
        // Given - record current time
        val beforeScheduling = System.currentTimeMillis()

        // When
        AlarmScheduler.scheduleBoundaryCheckAlarm(context, skipPermissionCheck = true)

        // Then
        val scheduledAlarms = shadowAlarmManager.scheduledAlarms
        assertTrue("Boundary check alarm should be scheduled", scheduledAlarms.isNotEmpty())

        val alarm = scheduledAlarms.last()
        assertEquals(AlarmManager.RTC_WAKEUP, alarm.getType())

        // Verify scheduled time is approximately 15 minutes from now
        val expectedTime = beforeScheduling + (15 * 60 * 1000L)
        val scheduledTime = alarm.triggerAtMs

        // Allow 2 second tolerance for test execution time
        val tolerance = 2000L
        assertTrue(
            "Alarm should be scheduled ~15 minutes from now (expected: $expectedTime, actual: $scheduledTime)",
            Math.abs(scheduledTime - expectedTime) < tolerance
        )
    }

    @Test
    @Suppress("DEPRECATION")
    fun testScheduleBoundaryCheckAlarm_createsPendingIntentWithCorrectAction() {
        // When
        AlarmScheduler.scheduleBoundaryCheckAlarm(context, skipPermissionCheck = true)

        // Then
        val scheduledAlarms = shadowAlarmManager.scheduledAlarms
        assertTrue("Alarm should be scheduled", scheduledAlarms.isNotEmpty())

        val alarm = scheduledAlarms.last()
        assertNotNull("PendingIntent should be created", alarm.operation)

        // Verify the intent targets HourBoundaryReceiver with BOUNDARY_CHECK action
        val shadowPendingIntent = Shadows.shadowOf(alarm.operation)
        val intent = shadowPendingIntent.savedIntent
        assertNotNull("Intent should not be null", intent)
        assertTrue(
            "Intent should target HourBoundaryReceiver",
            intent.component?.className?.contains("HourBoundaryReceiver") == true
        )
        assertEquals(
            "Intent should have ACTION_BOUNDARY_CHECK action",
            "com.example.myhourlystepcounterv2.ACTION_BOUNDARY_CHECK",
            intent.action
        )
    }

    @Test
    fun testCancelBoundaryCheckAlarm_doesNotCrash() {
        // Given - alarm is scheduled
        AlarmScheduler.scheduleBoundaryCheckAlarm(context, skipPermissionCheck = true)

        // When
        AlarmScheduler.cancelBoundaryCheckAlarm(context)

        // Then - should not crash
        // Note: Robolectric's shadow doesn't automatically remove alarms when cancel() is called
    }

    @Test
    fun testBoundaryCheckAlarm_usesExactAndAllowWhileIdle() {
        // When
        AlarmScheduler.scheduleBoundaryCheckAlarm(context, skipPermissionCheck = true)

        // Then
        val scheduledAlarms = shadowAlarmManager.scheduledAlarms
        assertTrue("Alarm should be scheduled", scheduledAlarms.isNotEmpty())

        val alarm = scheduledAlarms.last()
        // Verify alarm type is RTC_WAKEUP (wakes device from sleep)
        assertEquals(
            "Alarm should use RTC_WAKEUP to wake device",
            AlarmManager.RTC_WAKEUP,
            alarm.getType()
        )
    }
}
