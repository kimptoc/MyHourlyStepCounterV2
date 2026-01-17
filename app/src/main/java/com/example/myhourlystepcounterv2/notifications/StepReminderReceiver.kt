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
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_STEP_REMINDER) return

        // Use goAsync() to extend BroadcastReceiver lifecycle for coroutine work
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.Default + SupervisorJob()).launch {
            try {
                val preferences = StepPreferences(context.applicationContext)

                // Check if current time is within allowed window (8am to 10pm)
                val currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
                if (currentHour < 8 || currentHour >= 23) { // 8am to 10pm (23:00)
                    android.util.Log.d("StepReminder", "Outside allowed time window (8am-10pm), skipping")
                    // Still reschedule for next hour to continue the cycle
                    AlarmScheduler.scheduleStepReminders(context.applicationContext)
                    return@launch
                }

                // Check if reminder notifications are enabled
                val enabled = preferences.reminderNotificationEnabled.first()
                if (!enabled) {
                    android.util.Log.d("StepReminder", "Reminder notifications disabled, skipping")
                    // Still reschedule for next hour to continue the cycle
                    AlarmScheduler.scheduleStepReminders(context.applicationContext)
                    return@launch
                }

                // Check if we already sent notification this hour
                val lastNotificationTime = preferences.lastReminderNotificationTime.first()
                val currentHourStart = getCurrentHourStart()

                if (lastNotificationTime >= currentHourStart) {
                    android.util.Log.d("StepReminder", "Already sent notification this hour, skipping")
                    // Still reschedule for next hour to continue the cycle
                    AlarmScheduler.scheduleStepReminders(context.applicationContext)
                    return@launch
                }

                // Reset achievement tracking for new hour
                preferences.saveAchievementSentThisHour(false)

                // Get current hourly step count from shared singleton sensor
                val sensorManager = StepSensorManager.getInstance(context.applicationContext)

                // Read current step count from singleton (already initialized by ViewModel)
                val currentHourSteps = sensorManager.currentStepCount.first()

                android.util.Log.d(
                    "StepReminder",
                    "Current hour steps: $currentHourSteps (threshold: ${StepTrackerConfig.STEP_REMINDER_THRESHOLD})"
                )

                // Send notification if below threshold
                if (currentHourSteps < StepTrackerConfig.STEP_REMINDER_THRESHOLD) {
                    NotificationHelper.sendStepReminderNotification(
                        context.applicationContext,
                        currentHourSteps
                    )

                    // Record notification time and state
                    preferences.saveLastReminderNotificationTime(System.currentTimeMillis())
                    preferences.saveReminderSentThisHour(true)

                    android.util.Log.i(
                        "StepReminder",
                        "Sent reminder notification: $currentHourSteps steps < ${StepTrackerConfig.STEP_REMINDER_THRESHOLD}"
                    )
                } else {
                    android.util.Log.d(
                        "StepReminder",
                        "No notification needed: $currentHourSteps steps >= ${StepTrackerConfig.STEP_REMINDER_THRESHOLD}"
                    )
                }

                // Reschedule the next alarm (1 hour from now at XX:50)
                AlarmScheduler.scheduleStepReminders(context.applicationContext)
                android.util.Log.d("StepReminder", "Rescheduled next alarm for 1 hour from now")
            } catch (e: Exception) {
                android.util.Log.e("StepReminder", "Error in reminder receiver", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun getCurrentHourStart(): Long {
        return Calendar.getInstance().apply {
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
