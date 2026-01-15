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
     * Schedule exact alarm at 50 minutes past the current/next hour (XX:50)
     * Uses setExactAndAllowWhileIdle for precise timing even during doze mode
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

        // Create explicit intent to target the receiver directly
        // With exported="false" in manifest, this prevents duplicate deliveries
        val intent = Intent(context, StepReminderReceiver::class.java).apply {
            action = StepReminderReceiver.ACTION_STEP_REMINDER
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

        // Use setExactAndAllowWhileIdle for precise timing even during doze mode
        // Receiver will reschedule the next alarm after execution
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            calendar.timeInMillis,
            pendingIntent
        )

        android.util.Log.i(
            "AlarmScheduler",
            "Step reminder scheduled (exact) at ${calendar.time} (:50)"
        )
    }

    /**
     * Cancel scheduled step reminders
     */
    fun cancelStepReminders(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        // Create intent matching the one used for scheduling
        val intent = Intent(context, StepReminderReceiver::class.java).apply {
            action = StepReminderReceiver.ACTION_STEP_REMINDER
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
     * Schedule exact alarm at the start of the current/next hour (XX:00)
     * Uses setExactAndAllowWhileIdle for precise timing even during doze mode
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

        // Create explicit intent to target the receiver directly
        // With exported="false" in manifest, this prevents duplicate deliveries
        val intent = Intent(context, HourBoundaryReceiver::class.java).apply {
            action = HourBoundaryReceiver.ACTION_HOUR_BOUNDARY
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

        // Use setExactAndAllowWhileIdle for precise timing even during doze mode
        // Receiver will reschedule the next alarm after execution
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            calendar.timeInMillis,
            pendingIntent
        )

        android.util.Log.i(
            "AlarmScheduler",
            "Hour boundary alarm scheduled (exact) at ${calendar.time} (:00)"
        )
    }

    /**
     * Cancel scheduled hour boundary alarms
     */
    fun cancelHourBoundaryAlarms(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        // Create intent matching the one used for scheduling
        val intent = Intent(context, HourBoundaryReceiver::class.java).apply {
            action = HourBoundaryReceiver.ACTION_HOUR_BOUNDARY
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
