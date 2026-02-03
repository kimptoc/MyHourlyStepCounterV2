package com.example.myhourlystepcounterv2.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.myhourlystepcounterv2.MainActivity
import com.example.myhourlystepcounterv2.R
import com.example.myhourlystepcounterv2.StepTrackerConfig

object NotificationHelper {
    private const val CHANNEL_ID = "step_reminder_channel"
    private const val URGENT_CHANNEL_ID = "step_reminder_urgent_channel"
    private const val NOTIFICATION_ID = 100
    private const val ACHIEVEMENT_NOTIFICATION_ID = 101

    fun sendStepReminderNotification(context: Context, currentSteps: Int) {
        createReminderNotificationChannel(context)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.reminder_notification_title))
            .setContentText(
                context.getString(
                    R.string.reminder_notification_text,
                    currentSteps,
                    StepTrackerConfig.STEP_REMINDER_THRESHOLD
                )
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setVibrate(StepTrackerConfig.FIRST_REMINDER_VIBRATION_PATTERN)
            .build()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    fun sendSecondStepReminderNotification(context: Context, currentSteps: Int) {
        createUrgentReminderNotificationChannel(context)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            2, // Different request code
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, URGENT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.second_reminder_notification_title))
            .setContentText(
                context.getString(
                    R.string.second_reminder_notification_text,
                    currentSteps,
                    StepTrackerConfig.STEP_REMINDER_THRESHOLD
                )
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setVibrate(StepTrackerConfig.URGENT_REMINDER_VIBRATION_PATTERN)
            .setSound(null) // No sound, vibration only
            .build()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // Use same notification ID to replace the first reminder
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    fun sendStepAchievementNotification(context: Context, currentSteps: Int) {
        createReminderNotificationChannel(context)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            1,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.achievement_notification_title))
            .setContentText(
                context.getString(
                    R.string.achievement_notification_text,
                    currentSteps
                )
            )
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setVibrate(longArrayOf(0, 200, 100, 200, 100))
            .build()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(ACHIEVEMENT_NOTIFICATION_ID, notification)
    }

    private fun createReminderNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = context.getString(R.string.reminder_channel_name)
            val descriptionText = context.getString(R.string.reminder_channel_description)
            val importance = NotificationManager.IMPORTANCE_HIGH

            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                enableVibration(true)
                vibrationPattern = StepTrackerConfig.FIRST_REMINDER_VIBRATION_PATTERN
            }

            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createUrgentReminderNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = context.getString(R.string.reminder_channel_urgent_name)
            val descriptionText = context.getString(R.string.reminder_channel_urgent_description)
            val importance = NotificationManager.IMPORTANCE_HIGH

            val channel = NotificationChannel(URGENT_CHANNEL_ID, name, importance).apply {
                description = descriptionText
                enableVibration(true)
                vibrationPattern = StepTrackerConfig.URGENT_REMINDER_VIBRATION_PATTERN
                setSound(null, null)
            }

            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
}
