package com.example.myhourlystepcounterv2.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.example.myhourlystepcounterv2.R
import com.example.myhourlystepcounterv2.data.StepPreferences

class StepCounterForegroundService : android.app.Service() {
    companion object {
        const val CHANNEL_ID = "step_counter_channel_v2"
        const val NOTIFICATION_ID = 42
        const val ACTION_STOP = "com.example.myhourlystepcounterv2.ACTION_STOP_FOREGROUND"
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        val preferences = StepPreferences(applicationContext)
        val database = com.example.myhourlystepcounterv2.data.StepDatabase.getDatabase(applicationContext)
        val repository = com.example.myhourlystepcounterv2.data.StepRepository(database.stepDao())

        // Start foreground immediately with a placeholder notification
        try {
            startForeground(NOTIFICATION_ID, buildNotification(0, 0))
        } catch (e: Exception) {
            android.util.Log.e("StepCounterFGSvc", "startForeground failed", e)
            // Can't start foreground (likely disallowed while app is background) — stop to avoid crash
            scope.cancel()
            stopSelf()
            return
        }

        // Observe flows and update notification / wake-lock accordingly
        scope.launch {
            combine(
                preferences.totalStepsDevice,
                preferences.hourStartStepCount,
                preferences.currentHourTimestamp,
                preferences.useWakeLock
            ) { totalSteps, hourStart, currentHourTimestamp, useWake ->
                // Calculate start of day
                val startOfDay = java.util.Calendar.getInstance().apply {
                    set(java.util.Calendar.HOUR_OF_DAY, 0)
                    set(java.util.Calendar.MINUTE, 0)
                    set(java.util.Calendar.SECOND, 0)
                    set(java.util.Calendar.MILLISECOND, 0)
                }.timeInMillis

                // Get daily total from database (excluding current hour)
                val dbTotal = repository.getTotalStepsForDayExcludingCurrentHour(startOfDay, currentHourTimestamp).first() ?: 0
                val currentHourSteps = (totalSteps - hourStart).coerceAtLeast(0)
                val dailyTotal = dbTotal + currentHourSteps

                Triple(currentHourSteps, dailyTotal, useWake)
            }.collect { (currentHourSteps, dailyTotal, useWake) ->
                // Update notification with correct daily total
                val notification = buildNotification(currentHourSteps, dailyTotal)
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTIFICATION_ID, notification)

                // Handle wake-lock
                handleWakeLock(useWake)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.action?.let { action ->
            if (action == ACTION_STOP) {
                stopForegroundService()
                return android.app.Service.START_NOT_STICKY
            }
        }
        // Keep service running
        return android.app.Service.START_STICKY
    }

    override fun onBind(intent: Intent?): android.os.IBinder? = null

    private fun buildNotification(currentHourSteps: Int, totalSteps: Int): Notification {
        val title = getString(R.string.app_name)
        val text = getString(R.string.notification_text_steps, currentHourSteps, totalSteps)

        val stopIntent = Intent(this, StepCounterForegroundService::class.java).apply {
            action = ACTION_STOP
        }

        val stopPending = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .addAction(0, getString(R.string.notification_action_stop), stopPending)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = getString(R.string.notification_channel_name)
            val descriptionText = getString(R.string.notification_channel_description)
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setShowBadge(false)
            }
            val notificationManager: NotificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun handleWakeLock(enable: Boolean) {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (enable) {
            if (wakeLock == null || wakeLock?.isHeld == false) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "myhourly:StepCounterWakeLock").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } else {
            wakeLock?.let {
                if (it.isHeld) it.release()
                wakeLock = null
            }
        }
    }

    private fun stopForegroundService() {
        // Release wake-lock if held
        handleWakeLock(false)
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        handleWakeLock(false)
        scope.cancel()
    }
}
