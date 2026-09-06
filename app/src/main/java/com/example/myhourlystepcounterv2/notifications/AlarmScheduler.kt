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
    private const val REQUEST_CODE_SECOND_REMINDER = 1003
    private const val REQUEST_CODE_BOUNDARY_CHECK = 1004

    // Check every 15 minutes for missed boundaries
    private const val BOUNDARY_CHECK_INTERVAL_MS = 15 * 60 * 1000L

    private const val TAG = "AlarmScheduler"

    /**
     * True when exact alarms may be used (SCHEDULE_EXACT_ALARM granted, or explicitly
     * overridden via [skipPermissionCheck]). Falls back to true below Android 12 where
     * the permission does not exist.
     */
    private fun canScheduleExactAlarms(context: Context, skipPermissionCheck: Boolean): Boolean {
        if (skipPermissionCheck) return true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return alarmManager.canScheduleExactAlarms()
    }

    /**
     * Schedule a device-waking alarm. Uses setExactAndAllowWhileIdle when the app holds
     * SCHEDULE_EXACT_ALARM (precise, e.g. an hour boundary). When exact alarms are not
     * granted, falls back to setAndAllowWhileIdle so the alarm still fires during doze
     * (possibly deferred) instead of being silently dropped — the WorkManager and
     * missed-boundary backfill logic absorb the small delay.
     */
    private fun scheduleWakeupAlarm(
        context: Context,
        skipPermissionCheck: Boolean,
        triggerAtMillis: Long,
        pendingIntent: PendingIntent,
        description: String
    ) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (canScheduleExactAlarms(context, skipPermissionCheck)) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            android.util.Log.i(TAG, "$description scheduled (exact) at ${java.util.Date(triggerAtMillis)}")
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            android.util.Log.w(
                TAG,
                "$description scheduled (inexact fallback - exact alarm permission not granted) at ${java.util.Date(triggerAtMillis)}"
            )
        }
    }

    /**
     * Schedule exact alarm at 50 minutes past the current/next hour (XX:50)
     * Uses setExactAndAllowWhileIdle for precise timing even during doze mode
     */
    fun scheduleStepReminders(context: Context, skipPermissionCheck: Boolean = false) {
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
        scheduleWakeupAlarm(
            context = context,
            skipPermissionCheck = skipPermissionCheck,
            triggerAtMillis = calendar.timeInMillis,
            pendingIntent = pendingIntent,
            description = "Step reminder (:50)"
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
     * Schedule exact alarm at 55 minutes past the current/next hour (XX:55)
     * This is the second, more urgent reminder if steps are still below threshold
     * Uses setExactAndAllowWhileIdle for precise timing even during doze mode
     */
    fun scheduleSecondStepReminder(context: Context, skipPermissionCheck: Boolean = false) {
        // Create explicit intent to target the receiver directly
        // Use same receiver but different request code
        val intent = Intent(context, StepReminderReceiver::class.java).apply {
            action = StepReminderReceiver.ACTION_SECOND_STEP_REMINDER
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_SECOND_REMINDER,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Calculate next XX:55 time
        val calendar = Calendar.getInstance().apply {
            if (get(Calendar.MINUTE) >= 55) {
                // If past :55, schedule for next hour
                add(Calendar.HOUR_OF_DAY, 1)
            }
            set(Calendar.MINUTE, 55)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        // Use setExactAndAllowWhileIdle for precise timing even during doze mode
        // Receiver will reschedule the next alarm after execution
        scheduleWakeupAlarm(
            context = context,
            skipPermissionCheck = skipPermissionCheck,
            triggerAtMillis = calendar.timeInMillis,
            pendingIntent = pendingIntent,
            description = "Second step reminder (:55)"
        )
    }

    /**
     * Schedule step reminder for the next in-window time (08:50).
     * Used when currently outside the 8am–10pm quiet hours window to avoid
     * waking the device every hour overnight.
     */
    fun scheduleStepRemindersNextWindow(context: Context, skipPermissionCheck: Boolean = false) {
        val intent = Intent(context, StepReminderReceiver::class.java).apply {
            action = StepReminderReceiver.ACTION_STEP_REMINDER
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, REQUEST_CODE_REMINDER, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Next day if after 10pm; same day if before 8am
        val calendar = Calendar.getInstance().apply {
            if (get(Calendar.HOUR_OF_DAY) >= 22) add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 8)
            set(Calendar.MINUTE, 50)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        scheduleWakeupAlarm(
            context = context,
            skipPermissionCheck = skipPermissionCheck,
            triggerAtMillis = calendar.timeInMillis,
            pendingIntent = pendingIntent,
            description = "Step reminder (next window 08:50)"
        )
    }

    /**
     * Schedule second step reminder for the next in-window time (08:55).
     * Used when currently outside the 8am–10pm quiet hours window.
     */
    fun scheduleSecondStepReminderNextWindow(context: Context, skipPermissionCheck: Boolean = false) {
        val intent = Intent(context, StepReminderReceiver::class.java).apply {
            action = StepReminderReceiver.ACTION_SECOND_STEP_REMINDER
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, REQUEST_CODE_SECOND_REMINDER, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Next day if after 10pm; same day if before 8am
        val calendar = Calendar.getInstance().apply {
            if (get(Calendar.HOUR_OF_DAY) >= 22) add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 8)
            set(Calendar.MINUTE, 55)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        scheduleWakeupAlarm(
            context = context,
            skipPermissionCheck = skipPermissionCheck,
            triggerAtMillis = calendar.timeInMillis,
            pendingIntent = pendingIntent,
            description = "Second step reminder (next window 08:55)"
        )
    }

    /**
     * Cancel scheduled second step reminder
     */
    fun cancelSecondStepReminder(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        // Create intent matching the one used for scheduling
        val intent = Intent(context, StepReminderReceiver::class.java).apply {
            action = StepReminderReceiver.ACTION_SECOND_STEP_REMINDER
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_SECOND_REMINDER,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE
        )

        pendingIntent?.let {
            alarmManager.cancel(it)
            it.cancel()
            android.util.Log.i("AlarmScheduler", "Second step reminder cancelled")
        }
    }

    /**
     * Schedule exact alarm at the start of the current/next hour (XX:00)
     * Uses setExactAndAllowWhileIdle for precise timing even during doze mode
     * This ensures the notification resets even when the app is backgrounded
     */
    fun scheduleHourBoundaryAlarms(context: Context, skipPermissionCheck: Boolean = false) {
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

        // Calculate next XX:00 time (always at least 1 hour from now)
        val calendar = Calendar.getInstance().apply {
            // Always add 1 hour to avoid rescheduling for the current hour
            // when called at exactly XX:00:00 from the receiver
            add(Calendar.HOUR_OF_DAY, 1)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        // Use setExactAndAllowWhileIdle for precise timing even during doze mode
        // Receiver will reschedule the next alarm after execution
        scheduleWakeupAlarm(
            context = context,
            skipPermissionCheck = skipPermissionCheck,
            triggerAtMillis = calendar.timeInMillis,
            pendingIntent = pendingIntent,
            description = "Hour boundary alarm (:00)"
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

    /**
     * Schedule periodic alarm every 15 minutes to check for missed hour boundaries
     * This provides a backup safety net if the main hour boundary alarm fails
     * Uses setExactAndAllowWhileIdle for precise timing even during doze mode
     */
    fun scheduleBoundaryCheckAlarm(context: Context, skipPermissionCheck: Boolean = false) {
        // Create explicit intent to target the receiver directly
        val intent = Intent(context, HourBoundaryReceiver::class.java).apply {
            action = "com.example.myhourlystepcounterv2.ACTION_BOUNDARY_CHECK"
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_BOUNDARY_CHECK,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Schedule 15 minutes from now
        val triggerTime = System.currentTimeMillis() + BOUNDARY_CHECK_INTERVAL_MS

        // Use setExactAndAllowWhileIdle for precise timing even during doze mode
        // Receiver will reschedule the next check after execution
        scheduleWakeupAlarm(
            context = context,
            skipPermissionCheck = skipPermissionCheck,
            triggerAtMillis = triggerTime,
            pendingIntent = pendingIntent,
            description = "Boundary check alarm (+15 min)"
        )
    }

    /**
     * Cancel scheduled boundary check alarms
     */
    fun cancelBoundaryCheckAlarm(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        // Create intent matching the one used for scheduling
        val intent = Intent(context, HourBoundaryReceiver::class.java).apply {
            action = "com.example.myhourlystepcounterv2.ACTION_BOUNDARY_CHECK"
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_BOUNDARY_CHECK,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE
        )

        pendingIntent?.let {
            alarmManager.cancel(it)
            it.cancel()
            android.util.Log.i("AlarmScheduler", "Boundary check alarm cancelled")
        }
    }
}
