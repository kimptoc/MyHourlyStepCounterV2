package com.example.myhourlystepcounterv2.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.minutes
import com.example.myhourlystepcounterv2.R
import com.example.myhourlystepcounterv2.data.StepPreferences
import com.example.myhourlystepcounterv2.StepTrackerConfig
import com.example.myhourlystepcounterv2.PermissionHelper
import com.example.myhourlystepcounterv2.resolveKnownTotalForInitialization

class StepCounterForegroundService : android.app.Service() {
    companion object {
        const val CHANNEL_ID = "step_counter_channel_v4"
        const val NOTIFICATION_ID = 42
        const val ACTION_STOP = "com.example.myhourlystepcounterv2.ACTION_STOP_FOREGROUND"

        // Staleness thresholds for sensor keepalive
        const val FLUSH_THRESHOLD_MS = 60_000L            // 1 min: flush FIFO before reading
        const val RE_REGISTER_THRESHOLD_MS = 5 * 60_000L  // 5 min: re-register after boundary
        const val DORMANT_THRESHOLD_MS = 10 * 60_000L     // 10 min: re-register in keepalive
        const val CHECKPOINT_INTERVAL_MINUTES = 5L
        const val STARTUP_SYNC_TIMEOUT_MS = 15_000L

        enum class SensorAction { NONE, FLUSH, RE_REGISTER }

        fun determineSensorAction(sensorAgeMs: Long, thresholdMs: Long, lastEventTimeMs: Long): SensorAction {
            // No event received yet — sensor is still initializing, don't re-register
            if (lastEventTimeMs == 0L) return SensorAction.NONE
            return when {
                sensorAgeMs > thresholdMs -> SensorAction.RE_REGISTER
                sensorAgeMs > FLUSH_THRESHOLD_MS -> SensorAction.FLUSH
                else -> SensorAction.NONE
            }
        }

        fun resolvePreviousHourTimestamp(
            currentHourTimestamp: Long,
            savedHourTimestamp: Long
        ): Long {
            val expectedPrevious = currentHourTimestamp - (60 * 60 * 1000)
            return if (savedHourTimestamp <= 0 ||
                savedHourTimestamp < expectedPrevious ||
                savedHourTimestamp > currentHourTimestamp
            ) {
                expectedPrevious
            } else {
                savedHourTimestamp
            }
        }

        fun isDeviceRebootDetected(currentBootCount: Int, savedBootCount: Int): Boolean {
            return currentBootCount > 0 &&
                savedBootCount > 0 &&
                currentBootCount != savedBootCount
        }

        fun shouldBreakCounterContinuity(
            currentDeviceTotal: Int,
            savedDeviceTotal: Int,
            rebootDetected: Boolean
        ): Boolean {
            if (rebootDetected) return true
            return currentDeviceTotal > 0 &&
                savedDeviceTotal > 0 &&
                currentDeviceTotal < savedDeviceTotal
        }

        fun shouldClearNotificationSyncState(
            currentSyncing: Boolean,
            lastSensorEventTimeMs: Long
        ): Boolean {
            return currentSyncing && lastSensorEventTimeMs > 0L
        }

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
    @Volatile private var lastStalenessLogTime: Long = 0
    @Volatile private var lastCheckpointSkipLogTime: Long = 0
    private val notificationSyncing = MutableStateFlow(true)

    @OptIn(FlowPreview::class)
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        preferences = StepPreferences(applicationContext)
        val database = com.example.myhourlystepcounterv2.data.StepDatabase.getDatabase(applicationContext)
        repository = com.example.myhourlystepcounterv2.data.StepRepository(database.stepDao())
        scope.launch {
            val bootCount = getCurrentBootCount()
            val savedBootCount = preferences.lastKnownBootCount.first()
            if (bootCount > 0 && savedBootCount <= 0) {
                preferences.saveLastKnownBootCount(bootCount)
            }
        }

        // Get singleton sensor manager — may or may not be initialized by ViewModel
        sensorManager = com.example.myhourlystepcounterv2.sensor.StepSensorManager.getInstance(applicationContext)
        android.util.Log.d("StepCounterFGSvc", "Using shared singleton StepSensorManager for real-time notification updates")

        if (PermissionHelper.hasActivityRecognitionPermission(applicationContext)) {
            sensorManager.startListening()
            android.util.Log.i("StepCounterFGSvc", "Sensor listener started from service")
        } else {
            android.util.Log.w("StepCounterFGSvc", "ACTIVITY_RECOGNITION permission missing - sensor listener not started")
        }

        // If the OS killed the process and restarted for this service (without UI),
        // the sensor singleton will be recreated with isInitialized=false.
        // Seed it from saved preferences so currentStepCount emits correct values.
        if (!sensorManager.sensorState.value.isInitialized) {
            scope.launch {
                try {
                    initializeSensorFromPreferences()
                } catch (e: Exception) {
                    android.util.Log.e("StepCounterFGSvc", "Error initializing sensor from preferences", e)
                }
            }
        } else {
            android.util.Log.d("StepCounterFGSvc", "Sensor already initialized (ViewModel active), skipping service-side init")
        }

        // Start foreground immediately with a placeholder notification
        try {
            startForeground(NOTIFICATION_ID, buildNotification(0, 0, isSyncing = true))
        } catch (e: Exception) {
            android.util.Log.e("StepCounterFGSvc", "startForeground failed", e)
            // Can't start foreground (likely disallowed while app is background) — stop to avoid crash
            scope.cancel()
            stopSelf()
            return
        }

        scope.launch {
            val probeStart = System.currentTimeMillis()
            sensorManager.flushSensor()
            val fresh = sensorManager.waitForSensorEventAfter(probeStart, STARTUP_SYNC_TIMEOUT_MS)
            notificationSyncing.value = !fresh
            if (fresh) {
                android.util.Log.i("StepCounterFGSvc", "Startup sync probe succeeded for notification")
            } else {
                android.util.Log.w("StepCounterFGSvc", "Startup sync probe timed out; notification stays in syncing state")
            }
        }

        // Keep combine/map pipelines pure: clear syncing state from a dedicated observer.
        scope.launch {
            sensorManager.sensorState.collect { state ->
                if (shouldClearNotificationSyncState(notificationSyncing.value, state.lastSensorEventTimeMs)) {
                    notificationSyncing.value = false
                    android.util.Log.i("StepCounterFGSvc", "Notification syncing cleared after first fresh sensor callback")
                }
            }
        }

        // Periodic snapshot/checkpoint loop (every 5 minutes):
        // - Save device-total snapshots for backfill accuracy
        // - Save in-hour DB checkpoint to reduce reboot loss window
        // - Keep sensor alive with flush/re-register heuristics
        scope.launch {
            while (isActive) {
                val lastEventTime = sensorManager.getLastSensorEventTime()
                val sensorAge = System.currentTimeMillis() - lastEventTime
                when (determineSensorAction(sensorAge, DORMANT_THRESHOLD_MS, lastEventTime)) {
                    SensorAction.RE_REGISTER -> {
                        android.util.Log.w(
                            "StepCounterFGSvc",
                            "Sensor dormant for ${sensorAge / 1000}s. Re-registering listener."
                        )
                        sensorManager.reRegisterListener()
                        delay(3000) // Wait for first event after re-registration
                    }
                    SensorAction.FLUSH -> {
                        android.util.Log.d(
                            "StepCounterFGSvc",
                            "Sensor data ${sensorAge / 1000}s old. Flushing FIFO before snapshot."
                        )
                        sensorManager.flushSensor()
                        delay(2000)
                    }
                    SensorAction.NONE -> { /* sensor is fresh */ }
                }

                val currentTotal = sensorManager.getCurrentTotalSteps()
                if (currentTotal > 0) {
                    preferences.saveDeviceTotalSnapshot(System.currentTimeMillis(), currentTotal)
                    saveCurrentHourCheckpoint(currentTotal)
                }

                delay(CHECKPOINT_INTERVAL_MINUTES.minutes)
            }
        }

        // Observe flows and update notification / wake-lock accordingly
        scope.launch {
            combine(
                sensorManager.currentStepCount,
                preferences.currentHourTimestamp,
                preferences.useWakeLock,
                notificationSyncing
            ) { currentHourSteps, currentHourTimestamp, useWake, isSyncing ->
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
                StepNotificationState(
                    currentHourSteps = currentHourSteps,
                    dailyTotal = dailyTotal,
                    useWakeLock = useWake,
                    isSyncing = isSyncing
                )
            }
            .sample(3.seconds)  // THROTTLE: Only emit once every 3 seconds to prevent notification rate limiting
            .collect { state ->
                logTimestampStaleness()
                android.util.Log.d("StepCounterFGSvc", "Notification update (throttled 3s): currentHour=${state.currentHourSteps}, daily=${state.dailyTotal}, syncing=${state.isSyncing}")

                // Update notification with correct daily total
                val notification = buildNotification(state.currentHourSteps, state.dailyTotal, state.isSyncing)
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTIFICATION_ID, notification)

                // Handle wake-lock
                handleWakeLock(state.useWakeLock)
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

    private fun buildNotification(currentHourSteps: Int, totalSteps: Int, isSyncing: Boolean = false): Notification {
        val title = getString(R.string.app_name)
        val text = if (isSyncing) {
            getString(R.string.notification_text_syncing, totalSteps)
        } else {
            getString(R.string.notification_text_steps, currentHourSteps, totalSteps)
        }

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

    private data class StepNotificationState(
        val currentHourSteps: Int,
        val dailyTotal: Int,
        val useWakeLock: Boolean,
        val isSyncing: Boolean
    )

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

            // Flush sensor FIFO before reading device total for backfill
            val sensorAgeForBackfill = System.currentTimeMillis() - sensorManager.getLastSensorEventTime()
            if (sensorAgeForBackfill > FLUSH_THRESHOLD_MS) {
                android.util.Log.w(
                    "StepCounterFGSvc",
                    "checkMissedHourBoundaries: Sensor data stale (${sensorAgeForBackfill / 1000}s old). Flushing FIFO..."
                )
                sensorManager.flushSensor()
                delay(2000)
            }

            val currentDeviceTotal = sensorManager.getCurrentTotalSteps()
            val previousHourStartSteps = preferences.hourStartStepCount.first()
            val savedDeviceTotal = preferences.totalStepsDevice.first()
            val savedBootCount = preferences.lastKnownBootCount.first()
            val currentBootCount = getCurrentBootCount()
            val rebootDetected = isDeviceRebootDetected(currentBootCount, savedBootCount)

            val validSavedDeviceTotal = if (savedDeviceTotal == 0 && previousHourStartSteps > 0) {
                android.util.Log.w(
                    "StepCounterFGSvc",
                    "DETECTED BUG: savedDeviceTotal=0 but hourStartStepCount=$previousHourStartSteps. Using hourStartStepCount as fallback."
                )
                previousHourStartSteps
            } else {
                savedDeviceTotal
            }

            if (rebootDetected) {
                android.util.Log.w(
                    "StepCounterFGSvc",
                    "Device reboot detected (savedBootCount=$savedBootCount, currentBootCount=$currentBootCount). " +
                        "Breaking absolute-counter continuity for missed-hour backfill."
                )
            }

            val continuityBroken = shouldBreakCounterContinuity(
                currentDeviceTotal = currentDeviceTotal,
                savedDeviceTotal = validSavedDeviceTotal,
                rebootDetected = rebootDetected
            )
            if (continuityBroken) {
                android.util.Log.w(
                    "StepCounterFGSvc",
                    "Counter continuity broken (current=$currentDeviceTotal, saved=$validSavedDeviceTotal, reboot=$rebootDetected). " +
                        "Will preserve checkpointed data only and wait for post-boot baseline."
                )
            }

            val deviceTotalToUse = if (currentDeviceTotal > 0) {
                currentDeviceTotal
            } else if (rebootDetected) {
                android.util.Log.w(
                    "StepCounterFGSvc",
                    "Sensor not initialized after reboot (currentDeviceTotal=0). Avoiding stale fallback from pre-reboot total."
                )
                0
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

            val totalStepsWhileClosed = if (!continuityBroken && deviceTotalToUse > 0) {
                deviceTotalToUse - validSavedDeviceTotal
            } else {
                0
            }
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
                        android.util.Log.i(
                            "StepCounterFGSvc",
                            "Preferences synced at missed boundary: baseline=$deviceTotalToUse, timestamp=$currentHourTimestamp, total=$deviceTotalToUse"
                        )
                        preferences.saveLastProcessedBoundaryTimestamp(currentHourTimestamp)
                        lastProcessedBoundaryTimestamp = currentHourTimestamp
                        if (currentBootCount > 0) {
                            preferences.saveLastKnownBootCount(currentBootCount)
                        }
                        preferences.saveReminderSentThisHour(false)
                        preferences.saveSecondReminderSentThisHour(false)
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
            syncStartOfDay()
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
            var previousHourTimestamp = preferences.currentHourTimestamp.first()
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

            val expectedPreviousHour = currentHourTimestamp - (60 * 60 * 1000)
            val gapHours = if (previousHourTimestamp > 0) {
                (currentHourTimestamp - previousHourTimestamp) / (60 * 60 * 1000)
            } else {
                0
            }

            if (gapHours > 1) {
                android.util.Log.w(
                    "StepCounterFGSvc",
                    "handleHourBoundary: Detected stale previousHourTimestamp=${java.util.Date(previousHourTimestamp)} " +
                            "(gap=$gapHours hours). Running missed-hour backfill before saving."
                )
                checkMissedHourBoundaries()
                previousHourTimestamp = preferences.currentHourTimestamp.first()
                if (previousHourTimestamp < expectedPreviousHour || previousHourTimestamp > currentHourTimestamp) {
                    android.util.Log.w(
                        "StepCounterFGSvc",
                        "handleHourBoundary: Backfill did not advance hour timestamp (now=${java.util.Date(previousHourTimestamp)}). Will clamp to expected."
                    )
                }
            }

            val resolvedPreviousHour = resolvePreviousHourTimestamp(
                currentHourTimestamp = currentHourTimestamp,
                savedHourTimestamp = previousHourTimestamp
            )
            if (resolvedPreviousHour != previousHourTimestamp) {
                android.util.Log.w(
                    "StepCounterFGSvc",
                    "handleHourBoundary: Correcting previousHourTimestamp from ${java.util.Date(previousHourTimestamp)} " +
                            "to expected ${java.util.Date(expectedPreviousHour)}"
                )
                previousHourTimestamp = resolvedPreviousHour
            }

            val previousHourStartStepCount = preferences.hourStartStepCount.first()

            // Flush sensor FIFO to get latest step count before saving the hour.
            // During Doze, events may be batched in the hardware FIFO.
            val sensorAgeAtBoundary = System.currentTimeMillis() - sensorManager.getLastSensorEventTime()
            if (sensorAgeAtBoundary > FLUSH_THRESHOLD_MS) {
                android.util.Log.w(
                    "StepCounterFGSvc",
                    "handleHourBoundary: Sensor data stale (${sensorAgeAtBoundary / 1000}s old). Flushing FIFO..."
                )
                sensorManager.flushSensor()
                delay(2000) // Wait for flush callback to deliver via onSensorChanged
                val postFlushAge = System.currentTimeMillis() - sensorManager.getLastSensorEventTime()
                android.util.Log.d(
                    "StepCounterFGSvc",
                    "handleHourBoundary: Post-flush sensor age=${postFlushAge / 1000}s"
                )
            }

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

            syncStartOfDay()

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
                val currentBootCount = getCurrentBootCount()
                if (currentBootCount > 0) {
                    preferences.saveLastKnownBootCount(currentBootCount)
                }
                android.util.Log.i(
                    "StepCounterFGSvc",
                    "Preferences synced at hour boundary: baseline=$deviceTotal, timestamp=$currentHourTimestamp, total=$deviceTotal"
                )

                // Reset reminder/achievement flags for new hour
                preferences.saveReminderSentThisHour(false)
                preferences.saveSecondReminderSentThisHour(false)
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
     * Initialize the sensor manager from saved preferences when the ViewModel hasn't done it.
     * This handles the case where the OS kills and restarts the process for the foreground service
     * without the user opening the UI (so ViewModel.initialize() never runs).
     *
     * Mirrors the logic in StepCounterViewModel.initialize() lines 164-193.
     */
    private suspend fun initializeSensorFromPreferences() {
        // Double-check: ViewModel may have initialized between our check and this coroutine running
        if (sensorManager.sensorState.value.isInitialized) {
            android.util.Log.d("StepCounterFGSvc", "initializeSensorFromPreferences: Already initialized (race ok), skipping")
            return
        }

        val savedHourTimestamp = preferences.currentHourTimestamp.first()
        val currentHourTimestamp = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        val savedBootCount = preferences.lastKnownBootCount.first()
        val currentBootCount = getCurrentBootCount()
        if (savedBootCount <= 0 && currentBootCount > 0) {
            preferences.saveLastKnownBootCount(currentBootCount)
        }
        val rebootDetected = isDeviceRebootDetected(currentBootCount, savedBootCount)

        if (rebootDetected) {
            android.util.Log.w(
                "StepCounterFGSvc",
                "initializeSensorFromPreferences: boot count changed ($savedBootCount -> $currentBootCount). " +
                    "Ignoring pre-reboot cached totals."
            )
        }

        if (!rebootDetected && savedHourTimestamp == currentHourTimestamp) {
            // Same hour as last save — seed from saved baseline (ViewModel Branch 2)
            val baselineCandidate = preferences.hourStartStepCount.first()
            val savedTotal = preferences.totalStepsDevice.first()
            val currentDeviceSteps = sensorManager.getCurrentTotalSteps()
            val hasFreshSensorEvent = sensorManager.getLastSensorEventTime() > 0L

            val baseline = if (baselineCandidate > 0) baselineCandidate else maxOf(savedTotal, currentDeviceSteps)
            val knownTotal = resolveKnownTotalForInitialization(
                savedTotal = savedTotal,
                baseline = baseline,
                currentDeviceSteps = currentDeviceSteps,
                hasFreshSensorEvent = hasFreshSensorEvent
            )

            sensorManager.setLastHourStartStepCount(baseline)
            sensorManager.setLastKnownStepCount(knownTotal)
            sensorManager.markInitialized()

            android.util.Log.i(
                "StepCounterFGSvc",
                "initializeSensorFromPreferences: Seeded from saved prefs (same hour). " +
                        "baseline=$baseline, knownTotal=$knownTotal, savedHour=${java.util.Date(savedHourTimestamp)}"
            )
        } else {
            // Different hour or no saved timestamp — use current device total as baseline (ViewModel Branch 3)
            var currentDeviceSteps = sensorManager.getCurrentTotalSteps()

            // If sensor hasn't delivered an event yet, try fallback from preferences
            if (currentDeviceSteps <= 0) {
                val fallback = preferences.totalStepsDevice.first()
                if (!rebootDetected && fallback > 0) {
                    currentDeviceSteps = fallback
                    android.util.Log.w(
                        "StepCounterFGSvc",
                        "initializeSensorFromPreferences: Sensor not ready, using preferences fallback=$fallback"
                    )
                }
            }

            if (currentDeviceSteps > 0) {
                sensorManager.setLastHourStartStepCount(currentDeviceSteps)
                sensorManager.setLastKnownStepCount(currentDeviceSteps)
                sensorManager.markInitialized()

                preferences.saveHourData(
                    hourStartStepCount = currentDeviceSteps,
                    currentTimestamp = currentHourTimestamp,
                    totalSteps = currentDeviceSteps
                )
                if (currentBootCount > 0) {
                    preferences.saveLastKnownBootCount(currentBootCount)
                }

                android.util.Log.i(
                    "StepCounterFGSvc",
                    "initializeSensorFromPreferences: Cold start/stale prefs. " +
                            "Seeded from device total=$currentDeviceSteps, newHour=${java.util.Date(currentHourTimestamp)}"
                )
            } else {
                android.util.Log.w(
                    "StepCounterFGSvc",
                    "initializeSensorFromPreferences: No sensor data and no fallback available. " +
                            "Will initialize when first sensor event arrives."
                )
            }
        }
    }

    private suspend fun syncStartOfDay() {
        val currentStartOfDay = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis

        val storedStartOfDay = preferences.lastStartOfDay.first()
        if (storedStartOfDay == 0L || storedStartOfDay != currentStartOfDay) {
            val message = if (storedStartOfDay == 0L) {
                "Initializing lastStartOfDay to ${java.util.Date(currentStartOfDay)}"
            } else {
                "DAY BOUNDARY: Detected day change from ${java.util.Date(storedStartOfDay)} to ${java.util.Date(currentStartOfDay)}"
            }
            android.util.Log.i("StepCounterFGSvc", message)
            preferences.saveStartOfDay(currentStartOfDay)
        }
    }

    private suspend fun logTimestampStaleness() {
        val currentHourTimestamp = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        val savedHourTimestamp = preferences.currentHourTimestamp.first()
        val driftMs = currentHourTimestamp - savedHourTimestamp
        val twoHoursMs = 2 * 60 * 60 * 1000L
        if (savedHourTimestamp > 0 && driftMs > twoHoursMs) {
            val now = System.currentTimeMillis()
            if (now - lastStalenessLogTime > twoHoursMs) {
                lastStalenessLogTime = now
                android.util.Log.e(
                    "StepCounterFGSvc",
                    "Stale currentHourTimestamp detected while service running: " +
                        "saved=${java.util.Date(savedHourTimestamp)} current=${java.util.Date(currentHourTimestamp)} " +
                        "driftHours=${driftMs / (60 * 60 * 1000)}"
                )
            }
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
            handleBoundary = {
            handleHourBoundary()
            // Re-register sensor only if it was stale during this boundary
            val postBoundaryAge = System.currentTimeMillis() - sensorManager.getLastSensorEventTime()
            if (postBoundaryAge > RE_REGISTER_THRESHOLD_MS) {
                android.util.Log.w(
                    "StepCounterFGSvc",
                    "Sensor stale after boundary (${postBoundaryAge / 1000}s). Re-registering."
                )
                sensorManager.reRegisterListener()
            }
        },
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

    private fun getCurrentBootCount(): Int {
        return try {
            Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT)
        } catch (_: Exception) {
            -1
        }
    }

    private suspend fun saveCurrentHourCheckpoint(currentDeviceTotal: Int) {
        val currentHourTimestamp = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        val baseline = preferences.hourStartStepCount.first()
        if (baseline <= 0) {
            val now = System.currentTimeMillis()
            val checkpointLogWindowMs = 30 * 60 * 1000L
            if (now - lastCheckpointSkipLogTime > checkpointLogWindowMs) {
                lastCheckpointSkipLogTime = now
                android.util.Log.d(
                    "StepCounterFGSvc",
                    "Checkpoint skipped: hour baseline unavailable yet (baseline=$baseline). " +
                        "Will checkpoint after first valid post-boot sensor baseline."
                )
            }
            return
        }

        val clampedSteps = (currentDeviceTotal - baseline).coerceIn(0, StepTrackerConfig.MAX_STEPS_PER_HOUR)
        repository.saveHourlySteps(currentHourTimestamp, clampedSteps)
        android.util.Log.d(
            "StepCounterFGSvc",
            "Checkpoint saved for ${java.util.Date(currentHourTimestamp)}: steps=$clampedSteps"
        )
    }
}
