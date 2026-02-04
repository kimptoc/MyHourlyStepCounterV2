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
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.minutes
import com.example.myhourlystepcounterv2.R
import com.example.myhourlystepcounterv2.data.StepPreferences
import com.example.myhourlystepcounterv2.StepTrackerConfig

class StepCounterForegroundService : android.app.Service() {
    companion object {
        const val CHANNEL_ID = "step_counter_channel_v4"
        const val NOTIFICATION_ID = 42
        const val ACTION_STOP = "com.example.myhourlystepcounterv2.ACTION_STOP_FOREGROUND"
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private lateinit var sensorManager: com.example.myhourlystepcounterv2.sensor.StepSensorManager
    private lateinit var preferences: StepPreferences
    private lateinit var repository: com.example.myhourlystepcounterv2.data.StepRepository
    private val hourBoundaryLoopRunner = HourBoundaryLoopRunner()

    // Health check variables for hour boundary loop
    private var lastSuccessfulHourBoundary: Long = 0
    private var consecutiveFailures: Int = 0
    @Volatile private var hourBoundaryLoopActive: Boolean = false
    @Volatile private var lastProcessedBoundaryTimestamp: Long = 0

    @OptIn(FlowPreview::class)
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

        // Periodic snapshot of device total for backfill accuracy (every 15 minutes)
        scope.launch {
            while (isActive) {
                val currentTotal = sensorManager.getCurrentTotalSteps()
                if (currentTotal > 0) {
                    preferences.saveDeviceTotalSnapshot(System.currentTimeMillis(), currentTotal)
                }
                delay(15.minutes)
            }
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

        // Hour boundary detection with multi-layer error recovery
        startHourBoundaryLoopWithRecovery()

        // Schedule periodic boundary check alarm (every 15 minutes backup)
        com.example.myhourlystepcounterv2.notifications.AlarmScheduler.scheduleBoundaryCheckAlarm(applicationContext)
        android.util.Log.d("StepCounterFGSvc", "Boundary check alarm scheduled on service start")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.action?.let { action ->
            if (action == ACTION_STOP) {
                stopForegroundService()
                return android.app.Service.START_NOT_STICKY
            }
        }

        // Defense in depth: Check for missed boundaries whenever service receives any command
        scope.launch {
            try {
                checkMissedHourBoundaries()
                android.util.Log.d("StepCounterFGSvc", "onStartCommand: Checked for missed boundaries")
            } catch (e: Exception) {
                android.util.Log.e("StepCounterFGSvc", "Error checking missed boundaries in onStartCommand", e)
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
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = getString(R.string.notification_channel_name)
            val descriptionText = getString(R.string.notification_channel_description)
            val importance = NotificationManager.IMPORTANCE_HIGH
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
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Check if any hour boundaries were missed while the service was stopped.
     * This handles the case where user disabled permanent notification and later re-enabled it.
     */
    private suspend fun checkMissedHourBoundaries() {
        try {
            // Calculate current hour timestamp (what we're about to process)
            val currentHourTimestamp = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }.timeInMillis

            val savedHourTimestamp = preferences.currentHourTimestamp.first()
            val lastProcessed = preferences.lastProcessedBoundaryTimestamp.first()
            val effectiveLastProcessed = maxOf(lastProcessed, lastProcessedBoundaryTimestamp)

            // Deduplication: Skip if THIS hour was already processed
            if (currentHourTimestamp <= effectiveLastProcessed) {
                android.util.Log.d(
                    "StepCounterFGSvc",
                    "checkMissedHourBoundaries: Current hour $currentHourTimestamp already processed (effectiveLast=$effectiveLastProcessed), skipping"
                )
                return
            }

            if (savedHourTimestamp <= 0 || savedHourTimestamp >= currentHourTimestamp) {
                android.util.Log.d(
                    "StepCounterFGSvc",
                    "checkMissedHourBoundaries: No valid saved hour (saved=$savedHourTimestamp, current=$currentHourTimestamp), skipping"
                )
                return
            }

            val hoursDifference = (currentHourTimestamp - savedHourTimestamp) / (60 * 60 * 1000)
            if (hoursDifference <= 0) {
                android.util.Log.d("StepCounterFGSvc", "checkMissedHourBoundaries: No hour gap detected, skipping")
                return
            }

            val rangeStart = savedHourTimestamp
            val rangeEnd = currentHourTimestamp - (60 * 60 * 1000)
            if (rangeEnd < rangeStart) {
                android.util.Log.d("StepCounterFGSvc", "checkMissedHourBoundaries: Range end < start, skipping")
                return
            }

            val claimed = preferences.tryClaimBackfillRange(rangeStart, rangeEnd)
            if (!claimed) {
                android.util.Log.w(
                    "StepCounterFGSvc",
                    "checkMissedHourBoundaries: Backfill range already processed. start=${java.util.Date(rangeStart)}, end=${java.util.Date(rangeEnd)}"
                )
                return
            }

            android.util.Log.w(
                "StepCounterFGSvc",
                "Service restart detected: missed $hoursDifference hour boundaries. " +
                        "Backfill range: ${java.util.Date(rangeStart)} -> ${java.util.Date(rangeEnd)}"
            )

            val currentDeviceTotal = sensorManager.getCurrentTotalSteps()
            val previousHourStartSteps = preferences.hourStartStepCount.first()
            val savedDeviceTotal = preferences.totalStepsDevice.first()

            val validSavedDeviceTotal = if (savedDeviceTotal == 0 && previousHourStartSteps > 0) {
                android.util.Log.w(
                    "StepCounterFGSvc",
                    "DETECTED BUG: savedDeviceTotal=0 but hourStartStepCount=$previousHourStartSteps. Using hourStartStepCount as fallback."
                )
                previousHourStartSteps
            } else {
                savedDeviceTotal
            }

            val deviceTotalToUse = if (currentDeviceTotal > 0) {
                currentDeviceTotal
            } else if (validSavedDeviceTotal > 0) {
                android.util.Log.w(
                    "StepCounterFGSvc",
                    "Sensor not initialized yet (currentDeviceTotal=0), using fallback total=$validSavedDeviceTotal"
                )
                validSavedDeviceTotal
            } else {
                android.util.Log.e(
                    "StepCounterFGSvc",
                    "CRITICAL: Both currentDeviceTotal and savedDeviceTotal are 0. Cannot backfill safely."
                )
                0
            }

            val totalStepsWhileClosed = if (deviceTotalToUse > 0) deviceTotalToUse - validSavedDeviceTotal else 0
            if (totalStepsWhileClosed <= 0) {
                android.util.Log.w(
                    "StepCounterFGSvc",
                    "Backfill: totalStepsWhileClosed=$totalStepsWhileClosed. Skipping hour writes."
                )
            } else {
                val snapshots = preferences.getDeviceTotalSnapshots()
                val snapshotByHour = snapshots
                    .filter { it.timestamp in rangeStart until currentHourTimestamp }
                    .groupBy { ts ->
                        (ts.timestamp / (60 * 60 * 1000)) * (60 * 60 * 1000)
                    }
                    .mapValues { entry -> entry.value.maxByOrNull { it.timestamp }?.deviceTotal }

                val missingWithoutSnapshot = mutableListOf<Long>()
                var accountedSteps = 0
                var assignedSteps = 0
                var previousTotal = validSavedDeviceTotal
                var hourCursor = rangeStart

                while (hourCursor <= rangeEnd) {
                    val existing = repository.getStepForHour(hourCursor)
                    if (existing != null) {
                        accountedSteps += existing.stepCount
                        val snapTotal = snapshotByHour[hourCursor]
                        if (snapTotal != null && snapTotal >= previousTotal) {
                            previousTotal = snapTotal
                        }
                    } else {
                        val snapTotal = snapshotByHour[hourCursor]
                        if (snapTotal != null && snapTotal >= previousTotal) {
                            var stepsForHour = snapTotal - previousTotal
                            if (stepsForHour < 0) stepsForHour = 0
                            if (stepsForHour > StepTrackerConfig.MAX_STEPS_PER_HOUR) {
                                stepsForHour = StepTrackerConfig.MAX_STEPS_PER_HOUR
                            }
                            repository.saveHourlySteps(hourCursor, stepsForHour)
                            assignedSteps += stepsForHour
                            previousTotal = snapTotal
                        } else {
                            missingWithoutSnapshot.add(hourCursor)
                        }
                    }
                    hourCursor += (60 * 60 * 1000)
                }

                val remainingSteps = totalStepsWhileClosed - assignedSteps - accountedSteps
                if (missingWithoutSnapshot.isNotEmpty() && remainingSteps > 0) {
                    val stepsPerHour = remainingSteps / missingWithoutSnapshot.size
                    android.util.Log.i(
                        "StepCounterFGSvc",
                        "Backfill: Distributing remaining $remainingSteps steps across ${missingWithoutSnapshot.size} hours (~$stepsPerHour/hour)"
                    )
                    for (hourTs in missingWithoutSnapshot) {
                        val stepsClamped = minOf(stepsPerHour, StepTrackerConfig.MAX_STEPS_PER_HOUR)
                        repository.saveHourlySteps(hourTs, stepsClamped)
                    }
                } else if (missingWithoutSnapshot.isNotEmpty()) {
                    android.util.Log.w(
                        "StepCounterFGSvc",
                        "Backfill: Remaining steps $remainingSteps <= 0. Skipping distribution for ${missingWithoutSnapshot.size} hours."
                    )
                }
            }

            sensorManager.beginHourTransition()
            try {
                if (deviceTotalToUse > 0) {
                    val resetSuccessful = sensorManager.resetForNewHour(deviceTotalToUse)
                    if (resetSuccessful) {
                        preferences.saveHourData(
                            hourStartStepCount = deviceTotalToUse,
                            currentTimestamp = currentHourTimestamp,
                            totalSteps = deviceTotalToUse
                        )
                        preferences.saveLastProcessedBoundaryTimestamp(currentHourTimestamp)
                        lastProcessedBoundaryTimestamp = currentHourTimestamp
                        preferences.saveReminderSentThisHour(false)
                        preferences.saveAchievementSentThisHour(false)
                        android.util.Log.i(
                            "StepCounterFGSvc",
                            "Reset to current hour: baseline=$deviceTotalToUse, timestamp=$currentHourTimestamp"
                        )
                    }
                } else {
                    android.util.Log.w(
                        "StepCounterFGSvc",
                        "Skipping hour reset - waiting for valid sensor reading"
                    )
                }
            } finally {
                sensorManager.endHourTransition()
            }

            // Force immediate notification update after reset
            updateNotificationImmediately()
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
            // Calculate current hour timestamp (what we're about to process)
            val currentHourTimestamp = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }.timeInMillis

            // Get the PREVIOUS hour's data that needs to be saved
            val previousHourTimestamp = preferences.currentHourTimestamp.first()
            val lastProcessed = preferences.lastProcessedBoundaryTimestamp.first()
            val effectiveLastProcessed = maxOf(lastProcessed, lastProcessedBoundaryTimestamp)
            
            // Deduplication: Skip if THIS hour was already processed
            if (currentHourTimestamp <= effectiveLastProcessed) {
                android.util.Log.d(
                    "StepCounterFGSvc",
                    "handleHourBoundary: Current hour $currentHourTimestamp already processed (effectiveLast=$effectiveLastProcessed), skipping"
                )
                return
            }

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

            // Mark as processed BEFORE async operations to prevent races
            // Store the CURRENT boundary timestamp to prevent double processing
            preferences.saveLastProcessedBoundaryTimestamp(currentHourTimestamp)
            lastProcessedBoundaryTimestamp = currentHourTimestamp

            // Save the completed previous hour to database
            android.util.Log.i(
                "StepCounterFGSvc",
                "Saving completed hour: timestamp=$previousHourTimestamp (${java.util.Date(previousHourTimestamp)}), steps=$stepsInPreviousHour (device=$deviceTotal - baseline=$previousHourStartStepCount)"
            )
            repository.saveHourlySteps(previousHourTimestamp, stepsInPreviousHour)

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

            // Force immediate notification update after reset
            updateNotificationImmediately()

            // Reschedule alarm as backup (in case service stops)
            com.example.myhourlystepcounterv2.notifications.AlarmScheduler.scheduleHourBoundaryAlarms(applicationContext)
            android.util.Log.d("StepCounterFGSvc", "Rescheduled backup alarm for next hour")

            // Also reschedule boundary check alarm
            com.example.myhourlystepcounterv2.notifications.AlarmScheduler.scheduleBoundaryCheckAlarm(applicationContext)
            android.util.Log.d("StepCounterFGSvc", "Rescheduled boundary check alarm")
        } catch (e: Exception) {
            android.util.Log.e("StepCounterFGSvc", "Error processing hour boundary", e)
        }
    }

    /**
     * Force an immediate update of the notification, bypassing the 3-second throttle.
     */
    private suspend fun updateNotificationImmediately() {
        try {
            val currentHourSteps = sensorManager.currentStepCount.first()
            val currentHourTimestamp = preferences.currentHourTimestamp.first()
            
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

            android.util.Log.i("StepCounterFGSvc", "Forcing immediate notification update: hour=$currentHourSteps, daily=$dailyTotal")
            
            val notification = buildNotification(currentHourSteps, dailyTotal)
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            android.util.Log.e("StepCounterFGSvc", "Error forcing notification update", e)
        }
    }

    /**
     * Layer 2: Outer restart logic - restarts entire loop if it crashes
     */
    private fun startHourBoundaryLoopWithRecovery() {
        scope.launch {
            val maxRestarts = 10
            try {
                hourBoundaryLoopRunner.runWithRecovery(
                    maxRestarts = maxRestarts,
                    startLoop = { startHourBoundaryLoop() },
                    onRestart = { attempt, error ->
                        android.util.Log.e(
                            "StepCounterFGSvc",
                            "❌❌ Hour boundary loop crashed! Restart attempt $attempt/$maxRestarts",
                            error
                        )
                        android.util.Log.i(
                            "StepCounterFGSvc",
                            "Waiting ${minOf(5000L * attempt, 30000L)}ms before restart"
                        )
                    },
                    onGiveUp = {
                        android.util.Log.wtf(
                            "StepCounterFGSvc",
                            "💀 Hour boundary loop failed $maxRestarts times - GIVING UP. Service needs restart."
                        )
                        hourBoundaryLoopActive = false
                        // TODO: Consider sending notification to user about critical failure
                    }
                )
                android.util.Log.i("StepCounterFGSvc", "Hour boundary loop stopped normally")
            } catch (e: kotlinx.coroutines.CancellationException) {
                android.util.Log.w("StepCounterFGSvc", "Hour boundary loop cancelled intentionally")
            }
        }
    }

    /**
     * Layer 1: Inner loop with per-iteration error handling
     */
    private suspend fun startHourBoundaryLoop() {
        android.util.Log.i("StepCounterFGSvc", "Hour boundary detection loop starting")
        hourBoundaryLoopRunner.runInnerLoop(
            isActive = { hourBoundaryLoopActive },
            setActive = { active -> hourBoundaryLoopActive = active },
            checkMissed = { checkMissedHourBoundaries() },
            handleBoundary = { handleHourBoundary() },
            onBeforeDelay = { delayMs, nextHour, now ->
                android.util.Log.i(
                    "StepCounterFGSvc",
                    "Next hour boundary in ${delayMs}ms at ${nextHour.time} (current: ${now.time})"
                )
            },
            onBoundaryReached = {
                android.util.Log.i("StepCounterFGSvc", "Hour boundary reached at ${java.util.Calendar.getInstance().time}")
            },
            onIterationSuccess = {
                lastSuccessfulHourBoundary = System.currentTimeMillis()
                consecutiveFailures = 0
                android.util.Log.i("StepCounterFGSvc", "✅ Hour boundary completed successfully")
            },
            onIterationFailure = { error, failureCount ->
                consecutiveFailures = failureCount
                android.util.Log.e(
                    "StepCounterFGSvc",
                    "❌ Hour boundary processing failed (failure #$failureCount) but loop continues",
                    error
                )
                android.util.Log.i(
                    "StepCounterFGSvc",
                    "Waiting ${minOf(60000L * failureCount, 300000L)}ms before next attempt"
                )
            },
            onCheckMissedError = { error ->
                android.util.Log.e("StepCounterFGSvc", "Error checking missed boundaries (non-fatal)", error)
            }
        )
        android.util.Log.i("StepCounterFGSvc", "Hour boundary detection loop stopped")
    }

    /**
     * Health check for monitoring hour boundary loop status
     */
    fun isHourBoundaryLoopHealthy(): Boolean {
        val timeSinceLastSuccess = System.currentTimeMillis() - lastSuccessfulHourBoundary
        val maxGapMs = 2 * 60 * 60 * 1000 // 2 hours

        val isHealthy = hourBoundaryLoopActive &&
                       (lastSuccessfulHourBoundary == 0L || timeSinceLastSuccess < maxGapMs) &&
                       consecutiveFailures < 3

        if (!isHealthy) {
            android.util.Log.w(
                "StepCounterFGSvc",
                "⚠️ Hour boundary loop UNHEALTHY: active=$hourBoundaryLoopActive, " +
                "lastSuccess=${if (lastSuccessfulHourBoundary == 0L) "never" else "${timeSinceLastSuccess/1000}s ago"}, " +
                "failures=$consecutiveFailures"
            )
        }

        return isHealthy
    }

    override fun onDestroy() {
        super.onDestroy()
        hourBoundaryLoopActive = false  // Signal loop to stop
        handleWakeLock(false)
        // Don't stop the singleton sensor - ViewModel may still be using it
        scope.cancel()
    }
}
