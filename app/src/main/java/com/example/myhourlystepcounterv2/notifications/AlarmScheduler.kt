package com.example.myhourlystepcounterv2.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

object AlarmScheduler {
    private const val REQUEST_CODE_REMINDER = 1001
    private const val REQUEST_CODE_HOUR_BOUNDARY = 1002

    /**
     * Schedule repeating alarms at 50 minutes past each hour (XX:50)
     */
    fun scheduleStepReminders(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        // Check if SCHEDULE_EXACT_ALARM permission is granted (Android 12+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!alarmManager.canScheduleExactAlarms()) {
                android.util.Log.w(
                    "AlarmScheduler",
                    "Cannot schedule exact alarms - permission not granted"
                )
                return
            }
        }

        // Create explicit intent for Android 8.0+ compatibility
        val intent = Intent(context, StepReminderReceiver::class.java).apply {
            action = StepReminderReceiver.ACTION_STEP_REMINDER
            // Make it explicit by setting the component
            component = android.content.ComponentName(
                context.packageName,
                "com.example.myhourlystepcounterv2.notifications.StepReminderReceiver"
            )
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_REMINDER,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Calculate next XX:50 time
        val calendar = Calendar.getInstance().apply {
            if (get(Calendar.MINUTE) >= 50) {
                // If past :50, schedule for next hour
                add(Calendar.HOUR_OF_DAY, 1)
            }
            set(Calendar.MINUTE, 50)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val intervalMillis = 60 * 60 * 1000L // 1 hour

        // Use setRepeating for repeating alarms (more battery efficient)
        // Note: On Android 6.0+ this becomes inexact, but should still fire around :50
        alarmManager.setRepeating(
            AlarmManager.RTC_WAKEUP,
            calendar.timeInMillis,
            intervalMillis,
            pendingIntent
        )

        android.util.Log.i(
            "AlarmScheduler",
            "Step reminders scheduled starting at ${calendar.time} (every hour at :50)"
        )
    }

    /**
     * Cancel scheduled step reminders
     */
    fun cancelStepReminders(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        // Create explicit intent matching the one used for scheduling
        val intent = Intent(context, StepReminderReceiver::class.java).apply {
            action = StepReminderReceiver.ACTION_STEP_REMINDER
            component = android.content.ComponentName(
                context.packageName,
                "com.example.myhourlystepcounterv2.notifications.StepReminderReceiver"
            )
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_REMINDER,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE
        )

        pendingIntent?.let {
            alarmManager.cancel(it)
            it.cancel()
            android.util.Log.i("AlarmScheduler", "Step reminders cancelled")
        }
    }

    /**
     * Schedule repeating alarms at the start of each hour (XX:00)
     * This ensures the notification resets even when the app is backgrounded
     */
    fun scheduleHourBoundaryAlarms(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        // Check if SCHEDULE_EXACT_ALARM permission is granted (Android 12+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!alarmManager.canScheduleExactAlarms()) {
                android.util.Log.w(
                    "AlarmScheduler",
                    "Cannot schedule exact alarms - permission not granted"
                )
                return
            }
        }

        // Create explicit intent for Android 8.0+ compatibility
        val intent = Intent(context, HourBoundaryReceiver::class.java).apply {
            action = HourBoundaryReceiver.ACTION_HOUR_BOUNDARY
            // Make it explicit by setting the component
            component = android.content.ComponentName(
                context.packageName,
                "com.example.myhourlystepcounterv2.notifications.HourBoundaryReceiver"
            )
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_HOUR_BOUNDARY,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Calculate next XX:00 time
        val calendar = Calendar.getInstance().apply {
            if (get(Calendar.MINUTE) > 0 || get(Calendar.SECOND) > 0) {
                // If past :00, schedule for next hour
                add(Calendar.HOUR_OF_DAY, 1)
            }
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val intervalMillis = 60 * 60 * 1000L // 1 hour

        // Use setRepeating for repeating alarms (more battery efficient)
        // Note: On Android 6.0+ this becomes inexact, but should still fire around :00
        alarmManager.setRepeating(
            AlarmManager.RTC_WAKEUP,
            calendar.timeInMillis,
            intervalMillis,
            pendingIntent
        )

        android.util.Log.i(
            "AlarmScheduler",
            "Hour boundary alarms scheduled starting at ${calendar.time} (every hour at :00)"
        )
    }

    /**
     * Cancel scheduled hour boundary alarms
     */
    fun cancelHourBoundaryAlarms(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        // Create explicit intent matching the one used for scheduling
        val intent = Intent(context, HourBoundaryReceiver::class.java).apply {
            action = HourBoundaryReceiver.ACTION_HOUR_BOUNDARY
            component = android.content.ComponentName(
                context.packageName,
                "com.example.myhourlystepcounterv2.notifications.HourBoundaryReceiver"
            )
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_HOUR_BOUNDARY,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE
        )

        pendingIntent?.let {
            alarmManager.cancel(it)
            it.cancel()
            android.util.Log.i("AlarmScheduler", "Hour boundary alarms cancelled")
        }
    }
}
