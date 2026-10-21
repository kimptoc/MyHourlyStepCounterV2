package com.example.myhourlystepcounterv2.notifications

import android.content.Context
import android.util.Log
import com.example.myhourlystepcounterv2.data.StepPreferences
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Keeps the two step reminder alarms in line with the reminder settings: re-runs whenever the
 * toggle or either configured offset changes, replacing the pending alarms. Lives here rather
 * than inline in MainActivity so the settings-to-alarms path is testable (issue #42).
 */
object ReminderAlarmSync {
    private const val TAG = "ReminderAlarmSync"

    /** Collects the reminder settings until cancelled, applying each distinct combination. */
    suspend fun syncWithPreferences(
        context: Context,
        preferences: StepPreferences,
        skipPermissionCheck: Boolean = false
    ) {
        combine(
            preferences.reminderNotificationEnabled,
            preferences.firstReminderMinutesBefore,
            preferences.secondReminderMinutesBefore
        ) { enabled, first, second -> Triple(enabled, first, second) }
            .distinctUntilChanged()
            .collect { (enabled, firstMinutesBefore, secondMinutesBefore) ->
                apply(context, enabled, firstMinutesBefore, secondMinutesBefore, skipPermissionCheck)
            }
    }

    fun apply(
        context: Context,
        enabled: Boolean,
        firstMinutesBefore: Int,
        secondMinutesBefore: Int,
        skipPermissionCheck: Boolean = false
    ) {
        if (enabled) {
            AlarmScheduler.scheduleStepReminders(
                context,
                skipPermissionCheck = skipPermissionCheck,
                minutesBefore = firstMinutesBefore
            )
            AlarmScheduler.scheduleSecondStepReminder(
                context,
                skipPermissionCheck = skipPermissionCheck,
                minutesBefore = secondMinutesBefore
            )
            Log.d(
                TAG,
                "Step reminders scheduled ($firstMinutesBefore and $secondMinutesBefore min before the hour)"
            )
        } else {
            AlarmScheduler.cancelStepReminders(context)
            AlarmScheduler.cancelSecondStepReminder(context)
            Log.d(TAG, "Step reminders cancelled")
        }
    }
}
