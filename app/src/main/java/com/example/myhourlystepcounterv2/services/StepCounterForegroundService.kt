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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds
import com.example.myhourlystepcounterv2.R
import com.example.myhourlystepcounterv2.data.StepPreferences
import com.example.myhourlystepcounterv2.StepTrackerConfig

class StepCounterForegroundService : android.app.Service() {
    companion object {
        const val CHANNEL_ID = "step_counter_channel_v3"
        const val NOTIFICATION_ID = 42
        const val ACTION_STOP = "com.example.myhourlystepcounterv2.ACTION_STOP_FOREGROUND"
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private lateinit var sensorManager: com.example.myhourlystepcounterv2.sensor.StepSensorManager
    private lateinit var preferences: StepPreferences
    private lateinit var repository: com.example.myhourlystepcounterv2.data.StepRepository

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        preferences = StepPreferences(applicationContext)
        val database = com.example.myhourlystepcounterv2.data.StepDatabase.getDatabase(applicationContext)
        repository = com.example.myhourlystepcounterv2.data.StepRepository(database.stepDao())

        // Get singleton sensor manager (already initialized by ViewModel)
        sensorManager = com.example.myhourlystepcounterv2.sensor.StepSensorManager.getInstance(applicationContext)
        android.util.Log.d("StepCounterFGSvc", "Using shared singleton StepSensorManager for real-time notification updates")

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
                sensorManager.currentStepCount,
                preferences.currentHourTimestamp,
                preferences.useWakeLock
            ) { currentHourSteps, currentHourTimestamp, useWake ->
                android.util.Log.d("StepCounterFGSvc", "Live sensor: currentHourSteps=$currentHourSteps")

                // Calculate start of day
                val startOfDay = java.util.Calendar.getInstance().apply {
                    set(java.util.Calendar.HOUR_OF_DAY, 0)
                    set(java.util.Calendar.MINUTE, 0)
                    set(java.util.Calendar.SECOND, 0)
                    set(java.util.Calendar.MILLISECOND, 0)
                }.timeInMillis

                // Get daily total from database (excluding current hour)
                val dbTotal = repository.getTotalStepsForDayExcludingCurrentHour(startOfDay, currentHourTimestamp).first() ?: 0
                val dailyTotal = dbTotal + currentHourSteps

                android.util.Log.d("StepCounterFGSvc", "Calculated: dbTotal=$dbTotal, currentHour=$currentHourSteps, daily=$dailyTotal")
                Triple(currentHourSteps, dailyTotal, useWake)
            }
            .sample(3.seconds)  // THROTTLE: Only emit once every 3 seconds to prevent notification rate limiting
            .collect { (currentHourSteps, dailyTotal, useWake) ->
                android.util.Log.d("StepCounterFGSvc", "Notification update (throttled 3s): currentHour=$currentHourSteps, daily=$dailyTotal")

                // Update notification with correct daily total
                val notification = buildNotification(currentHourSteps, dailyTotal)
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTIFICATION_ID, notification)

                // Handle wake-lock
                handleWakeLock(useWake)
            }
        }

        // Hour boundary detection - runs continuously while service is active
        scope.launch {
            android.util.Log.i("StepCounterFGSvc", "Hour boundary detection coroutine started")

            // Check if we missed any hour boundaries while service was stopped
            checkMissedHourBoundaries()

            while (true) {
                val now = java.util.Calendar.getInstance()
                val nextHour = java.util.Calendar.getInstance().apply {
                    add(java.util.Calendar.HOUR_OF_DAY, 1)
                    set(java.util.Calendar.MINUTE, 0)
                    set(java.util.Calendar.SECOND, 0)
                    set(java.util.Calendar.MILLISECOND, 0)
                }

                val delayMs = nextHour.timeInMillis - now.timeInMillis
                android.util.Log.i(
                    "StepCounterFGSvc",
                    "Next hour boundary in ${delayMs}ms at ${nextHour.time} (current: ${now.time})"
                )

                delay(delayMs)

                // Hour boundary reached - execute logic
                android.util.Log.i("StepCounterFGSvc", "Hour boundary reached at ${java.util.Calendar.getInstance().time}")
                handleHourBoundary()
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

        val openAppIntent = Intent(this, com.example.myhourlystepcounterv2.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val openAppPending = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openAppPending)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setSilent(true)
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
                enableVibration(false)
                setSound(null, null)
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

    /**
     * Check if any hour boundaries were missed while the service was stopped.
     * This handles the case where user disabled permanent notification and later re-enabled it.
     */
    private suspend fun checkMissedHourBoundaries() {
        try {
            val currentHourTimestamp = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }.timeInMillis

            val savedHourTimestamp = preferences.currentHourTimestamp.first()

            if (savedHourTimestamp > 0 && savedHourTimestamp < currentHourTimestamp) {
                val hoursDifference = (currentHourTimestamp - savedHourTimestamp) / (60 * 60 * 1000)

                if (hoursDifference > 0) {
                    android.util.Log.w(
                        "StepCounterFGSvc",
                        "Service restart detected: missed $hoursDifference hour boundaries. " +
                                "Last saved hour: ${java.util.Date(savedHourTimestamp)}, " +
                                "current hour: ${java.util.Date(currentHourTimestamp)}"
                    )

                    // Save the incomplete hour with current steps (best effort)
                    val currentDeviceTotal = sensorManager.getCurrentTotalSteps()
                    val previousHourStartSteps = preferences.hourStartStepCount.first()

                    if (currentDeviceTotal > 0 && previousHourStartSteps > 0) {
                        var stepsInPreviousHour = currentDeviceTotal - previousHourStartSteps

                        // Validate
                        if (stepsInPreviousHour < 0) {
                            stepsInPreviousHour = 0
                        } else if (stepsInPreviousHour > StepTrackerConfig.MAX_STEPS_PER_HOUR) {
                            stepsInPreviousHour = StepTrackerConfig.MAX_STEPS_PER_HOUR
                        }

                        android.util.Log.i(
                            "StepCounterFGSvc",
                            "Saving missed hour: timestamp=$savedHourTimestamp, steps=$stepsInPreviousHour"
                        )
                        repository.saveHourlySteps(savedHourTimestamp, stepsInPreviousHour)
                    }

                    // Reset for current hour
                    sensorManager.beginHourTransition()
                    try {
                        val resetSuccessful = sensorManager.resetForNewHour(currentDeviceTotal)
                        if (resetSuccessful) {
                            preferences.saveHourData(
                                hourStartStepCount = currentDeviceTotal,
                                currentTimestamp = currentHourTimestamp,
                                totalSteps = currentDeviceTotal
                            )

                            // Reset notification flags for new hour
                            preferences.saveReminderSentThisHour(false)
                            preferences.saveAchievementSentThisHour(false)

                            android.util.Log.i(
                                "StepCounterFGSvc",
                                "Reset to current hour: baseline=$currentDeviceTotal, timestamp=$currentHourTimestamp"
                            )
                        }
                    } finally {
                        sensorManager.endHourTransition()
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("StepCounterFGSvc", "Error checking missed hour boundaries", e)
        }
    }

    /**
     * Handle hour boundary: save completed hour and reset for new hour.
     * Extracted from HourBoundaryReceiver for reuse in foreground service.
     */
    private suspend fun handleHourBoundary() {
        try {
            // Get the PREVIOUS hour's data that needs to be saved
            val previousHourTimestamp = preferences.currentHourTimestamp.first()
            val previousHourStartStepCount = preferences.hourStartStepCount.first()

            // Get current device total from sensor (or fallback to preferences)
            val currentDeviceTotal = sensorManager.getCurrentTotalSteps()
            val fallbackTotal = preferences.totalStepsDevice.first()

            val deviceTotal = if (currentDeviceTotal > 0) {
                currentDeviceTotal
            } else {
                android.util.Log.w(
                    "StepCounterFGSvc",
                    "Sensor returned 0, using preferences fallback: $fallbackTotal"
                )
                fallbackTotal
            }

            // Calculate steps in the PREVIOUS hour (that just ended)
            var stepsInPreviousHour = deviceTotal - previousHourStartStepCount

            // Validate and clamp the value
            if (stepsInPreviousHour < 0) {
                android.util.Log.w("StepCounterFGSvc", "Negative step delta ($stepsInPreviousHour). Clamping to 0.")
                stepsInPreviousHour = 0
            } else if (stepsInPreviousHour > StepTrackerConfig.MAX_STEPS_PER_HOUR) {
                android.util.Log.w("StepCounterFGSvc", "Unreasonable step delta ($stepsInPreviousHour). Clamping to ${StepTrackerConfig.MAX_STEPS_PER_HOUR}.")
                stepsInPreviousHour = StepTrackerConfig.MAX_STEPS_PER_HOUR
            }

            // Save the completed previous hour to database
            android.util.Log.i(
                "StepCounterFGSvc",
                "Saving completed hour: timestamp=$previousHourTimestamp (${java.util.Date(previousHourTimestamp)}), steps=$stepsInPreviousHour (device=$deviceTotal - baseline=$previousHourStartStepCount)"
            )
            repository.saveHourlySteps(previousHourTimestamp, stepsInPreviousHour)

            // Calculate current hour timestamp
            val currentHourTimestamp = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }.timeInMillis

            android.util.Log.i(
                "StepCounterFGSvc",
                "Processing hour boundary: deviceTotal=$deviceTotal, newHourTimestamp=$currentHourTimestamp (${java.util.Date(currentHourTimestamp)})"
            )

            // Check for day boundary (robust: handles service restarts, timezone changes)
            val currentStartOfDay = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, 0)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }.timeInMillis

            val storedStartOfDay = preferences.lastStartOfDay.first()

            // Update lastStartOfDay if: (1) never initialized, or (2) day changed
            if (storedStartOfDay == 0L || storedStartOfDay != currentStartOfDay) {
                val message = if (storedStartOfDay == 0L) {
                    "Initializing lastStartOfDay to ${java.util.Date(currentStartOfDay)}"
                } else {
                    "DAY BOUNDARY: Detected day change from ${java.util.Date(storedStartOfDay)} to ${java.util.Date(currentStartOfDay)}"
                }
                android.util.Log.i("StepCounterFGSvc", message)
                preferences.saveStartOfDay(currentStartOfDay)
            }

            // Begin hour transition - blocks sensor events from interfering
            sensorManager.beginHourTransition()

            try {
                // Reset sensor for new hour (updates display to 0)
                val resetSuccessful = sensorManager.resetForNewHour(deviceTotal)

                if (!resetSuccessful) {
                    android.util.Log.w("StepCounterFGSvc", "Baseline already set, skipping duplicate reset")
                    return
                }

                // Update preferences with new hour baseline
                preferences.saveHourData(
                    hourStartStepCount = deviceTotal,
                    currentTimestamp = currentHourTimestamp,
                    totalSteps = deviceTotal
                )

                // Reset reminder/achievement flags for new hour
                preferences.saveReminderSentThisHour(false)
                preferences.saveAchievementSentThisHour(false)

                android.util.Log.i(
                    "StepCounterFGSvc",
                    "✓ Hour boundary processed: Saved $stepsInPreviousHour steps, reset to baseline=$deviceTotal, display=0"
                )
            } finally {
                // End hour transition - resume sensor events
                sensorManager.endHourTransition()
            }

            // Reschedule alarm as backup (in case service stops)
            com.example.myhourlystepcounterv2.notifications.AlarmScheduler.scheduleHourBoundaryAlarms(applicationContext)
            android.util.Log.d("StepCounterFGSvc", "Rescheduled backup alarm for next hour")
        } catch (e: Exception) {
            android.util.Log.e("StepCounterFGSvc", "Error processing hour boundary", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handleWakeLock(false)
        // Don't stop the singleton sensor - ViewModel may still be using it
        scope.cancel()
    }
}
