package com.example.myhourlystepcounterv2.notifications

import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.myhourlystepcounterv2.data.StepPreferences
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import java.util.Calendar

/**
 * Issue #42: saving new reminder offsets in Profile must replace the pending reminder alarms.
 * MainActivity runs [ReminderAlarmSync.syncWithPreferences] for this.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ReminderAlarmSyncTest {

    private lateinit var context: Context
    private lateinit var preferences: StepPreferences
    private lateinit var shadowAlarmManager: ShadowAlarmManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        preferences = StepPreferences(context)
        runBlocking { preferences.clearAll() }
        shadowAlarmManager = Shadows.shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
    }

    /**
     * Minute-of-hour of each pending reminder alarm. Also checks every alarm is due within the
     * next hour, so a reminder scheduled at the right minute of the wrong hour still fails.
     */
    private fun scheduledReminderMinutes(): Set<Int> {
        val now = System.currentTimeMillis()
        val reminders = shadowAlarmManager.scheduledAlarms.filter {
            Shadows.shadowOf(it.operation).savedIntent.action in setOf(
                StepReminderReceiver.ACTION_STEP_REMINDER,
                StepReminderReceiver.ACTION_SECOND_STEP_REMINDER
            )
        }
        reminders.forEach {
            assertTrue(
                "Reminder due at ${java.util.Date(it.triggerAtMs)} is not within the next hour",
                it.triggerAtMs in now..now + 60 * 60 * 1000L
            )
        }
        return reminders
            .map { Calendar.getInstance().apply { timeInMillis = it.triggerAtMs }.get(Calendar.MINUTE) }
            .toSet()
    }

    /**
     * The collector is launched in the test's own runBlocking scope, so alarm scheduling and these
     * shadow reads happen on the same thread; the delay just yields to it.
     */
    private suspend fun awaitReminderMinutes(expected: Set<Int>) {
        withTimeout(5_000) {
            while (scheduledReminderMinutes() != expected) delay(20)
        }
    }

    @Test
    fun apply_enabled_schedulesBothRemindersAtConfiguredMinutes() {
        ReminderAlarmSync.apply(context, enabled = true, firstMinutesBefore = 12, secondMinutesBefore = 6, skipPermissionCheck = true)

        assertEquals(setOf(48, 54), scheduledReminderMinutes())
    }

    @Test
    fun apply_disabled_cancelsBothReminders() {
        ReminderAlarmSync.apply(context, enabled = true, firstMinutesBefore = 10, secondMinutesBefore = 5, skipPermissionCheck = true)

        ReminderAlarmSync.apply(context, enabled = false, firstMinutesBefore = 10, secondMinutesBefore = 5, skipPermissionCheck = true)

        assertTrue(scheduledReminderMinutes().isEmpty())
    }

    @Test
    fun savingNewOffsets_reschedulesTheReminderAlarms() = runBlocking {
        val sync = launch {
            ReminderAlarmSync.syncWithPreferences(context, preferences, skipPermissionCheck = true)
        }
        try {
            awaitReminderMinutes(setOf(50, 55))

            preferences.saveReminderMinutesBefore(20, 8)

            // The old :50/:55 alarms are replaced, not joined, by the new ones.
            awaitReminderMinutes(setOf(40, 52))
        } finally {
            sync.cancelAndJoin()
        }
    }

    @Test
    fun turningRemindersOff_cancelsTheReminderAlarms() = runBlocking {
        val sync = launch {
            ReminderAlarmSync.syncWithPreferences(context, preferences, skipPermissionCheck = true)
        }
        try {
            awaitReminderMinutes(setOf(50, 55))

            preferences.saveReminderNotificationEnabled(false)

            awaitReminderMinutes(emptySet())
        } finally {
            sync.cancelAndJoin()
        }
    }
}
