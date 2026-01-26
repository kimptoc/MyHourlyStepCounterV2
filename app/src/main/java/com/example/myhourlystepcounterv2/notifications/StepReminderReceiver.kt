package com.example.myhourlystepcounterv2.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import com.example.myhourlystepcounterv2.data.StepPreferences
import com.example.myhourlystepcounterv2.sensor.StepSensorManager
import com.example.myhourlystepcounterv2.StepTrackerConfig
import java.util.Calendar

class StepReminderReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_STEP_REMINDER = "com.example.myhourlystepcounterv2.ACTION_STEP_REMINDER"
        const val ACTION_SECOND_STEP_REMINDER = "com.example.myhourlystepcounterv2.ACTION_SECOND_STEP_REMINDER"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != ACTION_STEP_REMINDER && action != ACTION_SECOND_STEP_REMINDER) return

        val isSecondReminder = action == ACTION_SECOND_STEP_REMINDER

        // Use goAsync() to extend BroadcastReceiver lifecycle for coroutine work
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.Default + SupervisorJob()).launch {
            try {
                val preferences = StepPreferences(context.applicationContext)
                val reminderType = if (isSecondReminder) "Second (XX:55)" else "First (XX:50)"

                // Check if current time is within allowed window (8am to 10pm)
                val currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
                if (currentHour < 8 || currentHour >= 23) { // 8am to 10pm (23:00)
                    android.util.Log.d("StepReminder", "$reminderType: Outside allowed time window (8am-10pm), skipping")
                    // Still reschedule for next hour to continue the cycle
                    if (isSecondReminder) {
                        AlarmScheduler.scheduleSecondStepReminder(context.applicationContext)
                    } else {
                        AlarmScheduler.scheduleStepReminders(context.applicationContext)
                    }
                    return@launch
                }

                // Check if reminder notifications are enabled
                val enabled = preferences.reminderNotificationEnabled.first()
                if (!enabled) {
                    android.util.Log.d("StepReminder", "$reminderType: Reminder notifications disabled, skipping")
                    // Still reschedule for next hour to continue the cycle
                    if (isSecondReminder) {
                        AlarmScheduler.scheduleSecondStepReminder(context.applicationContext)
                    } else {
                        AlarmScheduler.scheduleStepReminders(context.applicationContext)
                    }
                    return@launch
                }

                if (isSecondReminder) {
                    // Second reminder (XX:55) logic
                    handleSecondReminderLogic(context.applicationContext, preferences)
                } else {
                    // First reminder (XX:50) logic
                    handleFirstReminderLogic(context.applicationContext, preferences)
                }
            } catch (e: Exception) {
                android.util.Log.e("StepReminder", "Error in reminder receiver", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun handleFirstReminderLogic(context: Context, preferences: StepPreferences) {
        // Check if we already sent notification this hour
        val lastNotificationTime = preferences.lastReminderNotificationTime.first()
        val currentHourStart = getCurrentHourStart()

        if (lastNotificationTime >= currentHourStart) {
            android.util.Log.d("StepReminder", "First: Already sent notification this hour, skipping")
            // Still reschedule for next hour to continue the cycle
            AlarmScheduler.scheduleStepReminders(context)
            return
        }

        // Reset achievement tracking for new hour
        preferences.saveAchievementSentThisHour(false)

        // Get current hourly step count from shared singleton sensor
        val sensorManager = StepSensorManager.getInstance(context)

        // Read current step count from singleton (already initialized by ViewModel)
        val currentHourSteps = sensorManager.currentStepCount.first()

        android.util.Log.d(
            "StepReminder",
            "First: Current hour steps: $currentHourSteps (threshold: ${StepTrackerConfig.STEP_REMINDER_THRESHOLD})"
        )

        // Send notification if below threshold
        if (currentHourSteps < StepTrackerConfig.STEP_REMINDER_THRESHOLD) {
            NotificationHelper.sendStepReminderNotification(
                context,
                currentHourSteps
            )

            // Record notification time and state
            preferences.saveLastReminderNotificationTime(System.currentTimeMillis())
            preferences.saveReminderSentThisHour(true)

            android.util.Log.i(
                "StepReminder",
                "First: Sent reminder notification: $currentHourSteps steps < ${StepTrackerConfig.STEP_REMINDER_THRESHOLD}"
            )
        } else {
            android.util.Log.d(
                "StepReminder",
                "First: No notification needed: $currentHourSteps steps >= ${StepTrackerConfig.STEP_REMINDER_THRESHOLD}"
            )
        }

        // Reschedule the next alarm (1 hour from now at XX:50)
        AlarmScheduler.scheduleStepReminders(context)
        android.util.Log.d("StepReminder", "First: Rescheduled next alarm for 1 hour from now")
    }

    private suspend fun handleSecondReminderLogic(context: Context, preferences: StepPreferences) {
        // Check if we already sent second reminder this hour
        val lastSecondReminderTime = preferences.lastSecondReminderTime.first()
        val currentHourStart = getCurrentHourStart()

        if (lastSecondReminderTime >= currentHourStart) {
            android.util.Log.d("StepReminder", "Second: Already sent notification this hour, skipping")
            // Still reschedule for next hour to continue the cycle
            AlarmScheduler.scheduleSecondStepReminder(context)
            return
        }

        // Get current hourly step count from shared singleton sensor
        val sensorManager = StepSensorManager.getInstance(context)

        // Read current step count from singleton (already initialized by ViewModel)
        val currentHourSteps = sensorManager.currentStepCount.first()

        android.util.Log.d(
            "StepReminder",
            "Second: Current hour steps: $currentHourSteps (threshold: ${StepTrackerConfig.STEP_REMINDER_THRESHOLD})"
        )

        // Send second reminder only if STILL below threshold
        if (currentHourSteps < StepTrackerConfig.STEP_REMINDER_THRESHOLD) {
            NotificationHelper.sendSecondStepReminderNotification(
                context,
                currentHourSteps
            )

            // Record notification time and state
            preferences.saveLastSecondReminderTime(System.currentTimeMillis())
            preferences.saveSecondReminderSentThisHour(true)

            android.util.Log.i(
                "StepReminder",
                "Second: Sent urgent reminder notification: $currentHourSteps steps < ${StepTrackerConfig.STEP_REMINDER_THRESHOLD}"
            )
        } else {
            android.util.Log.d(
                "StepReminder",
                "Second: No notification needed: $currentHourSteps steps >= ${StepTrackerConfig.STEP_REMINDER_THRESHOLD}"
            )
        }

        // Reschedule the next alarm (1 hour from now at XX:55)
        AlarmScheduler.scheduleSecondStepReminder(context)
        android.util.Log.d("StepReminder", "Second: Rescheduled next alarm for 1 hour from now")
    }

    private fun getCurrentHourStart(): Long {
        return Calendar.getInstance().apply {
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
