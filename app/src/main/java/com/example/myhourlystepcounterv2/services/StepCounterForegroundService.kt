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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
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
        const val ACTIVE_WINDOW_START_HOUR = 8
        const val ACTIVE_WINDOW_END_HOUR = 22
        const val MAX_TIMELINE_CIRCLES = 10

        // Staleness thresholds for sensor keepalive
        const val FLUSH_THRESHOLD_MS = 60_000L            // 1 min: flush FIFO before reading
        const val RE_REGISTER_THRESHOLD_MS = 5 * 60_000L  // 5 min: re-register after boundary
        const val DORMANT_THRESHOLD_MS = 10 * 60_000L     // 10 min: re-register in keepalive
        const val CHECKPOINT_INTERVAL_MINUTES = 5L
        const val STARTUP_SYNC_TIMEOUT_MS = 15_000L

        /**
         * Timeout for the short-lived work wake lock. A stalled coroutine can never pin
         * the CPU awake for longer than this because the lock auto-releases.
         */
        const val WORK_WAKE_LOCK_TIMEOUT_MS = 120_000L

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

        /**
         * Staleness threshold for checkpoint-driven notification updates.
         * When the last sensor event is older than this, the checkpoint loop
         * will feed its reading back to the notification pipeline.
         */
        const val STALE_SENSOR_THRESHOLD_MS = 30_000L

        /**
         * Determines whether the checkpoint loop should update the notification
         * pipeline with its own sensor reading. This is needed because
         * onSensorChanged() may not fire during doze/screen-off, leaving the
         * notification stuck at 0 after reboot.
         *
         * @param isInitialized whether the sensor state has been initialized
         * @param sensorAgeMs  how long since the last onSensorChanged() callback
         * @param currentTotal the device-total step count read by the checkpoint
         * @param hourBaseline the step count at the start of the current hour
         * @param displayedSteps the value currently shown in the notification
         * @return true if the checkpoint should push its reading to the notification
         */
        fun shouldCheckpointUpdateNotification(
            isInitialized: Boolean,
            sensorAgeMs: Long,
            currentTotal: Int,
            hourBaseline: Int,
            displayedSteps: Int
        ): Boolean {
            if (!isInitialized) return false
            if (sensorAgeMs <= STALE_SENSOR_THRESHOLD_MS) return false
            val estimatedHourSteps = currentTotal - hourBaseline
            return estimatedHourSteps >= 0 && estimatedHourSteps > displayedSteps
        }

        /**
         * Variant of [shouldCheckpointUpdateNotification] that accounts for the pre-reboot
         * offset captured at reboot detection time. The estimate is the post-reboot delta
         * plus the offset that represents steps walked before the most recent reboot in
         * the current hour.
         */
        fun shouldCheckpointUpdateNotificationWithOffset(
            isInitialized: Boolean,
            sensorAgeMs: Long,
            currentTotal: Int,
            hourBaseline: Int,
            preRebootOffset: Int,
            displayedSteps: Int
        ): Boolean {
            if (!isInitialized) return false
            if (sensorAgeMs <= STALE_SENSOR_THRESHOLD_MS) return false
            val rawDelta = currentTotal - hourBaseline
            if (rawDelta < 0) return false
            val estimatedHourSteps = rawDelta + maxOf(0, preRebootOffset)
            return estimatedHourSteps > displayedSteps
        }

        /**
         * Convert a saved sensor total + baseline pair into the in-hour step count that
         * was accumulated before a reboot. Clamped to [0, maxStepsPerHour].
         */
        fun computePreRebootInHourSteps(
            savedTotal: Int,
            savedBaseline: Int,
            maxStepsPerHour: Int
        ): Int {
            val delta = savedTotal - savedBaseline
            return delta.coerceIn(0, maxStepsPerHour)
        }

        /**
         * Sum an existing pre-reboot offset with the in-hour count from the most recent
         * reboot (handles the multi-reboot-in-same-hour case). Clamped to [0, maxStepsPerHour].
         */
        fun accumulatePreRebootOffset(
            currentOffset: Int,
            newInHourSteps: Int,
            maxStepsPerHour: Int
        ): Int {
            val safeCurrent = maxOf(0, currentOffset)
            val safeNew = maxOf(0, newInHourSteps)
            return (safeCurrent + safeNew).coerceIn(0, maxStepsPerHour)
        }

        /**
         * Compute the displayed in-hour step count by adding the pre-reboot offset to the
         * raw sensor delta. Raw delta is floored at 0; the offset is added on top; the
         * sum is capped at maxStepsPerHour to match the ceiling used by sibling helpers
         * and DB save paths.
         */
        fun computeDisplayedHourSteps(
            currentTotal: Int,
            hourBaseline: Int,
            preRebootOffset: Int,
            maxStepsPerHour: Int
        ): Int {
            val rawDelta = maxOf(0, currentTotal - hourBaseline)
            return (rawDelta + maxOf(0, preRebootOffset)).coerceAtMost(maxStepsPerHour)
        }

        /**
         * Compute the step count to persist for the just-completed hour at an hour boundary.
         * If the sensor's absolute counter is unreliable (continuityBroken), fall back to
         * only the offset — preserves pre-reboot steps even when post-reboot delta can't be
         * trusted. Clamped to [0, maxStepsPerHour].
         */
        fun computeStepsForBoundarySave(
            deviceTotal: Int,
            baseline: Int,
            preRebootOffset: Int,
            continuityBroken: Boolean,
            maxStepsPerHour: Int
        ): Int {
            val safeOffset = maxOf(0, preRebootOffset)
            return if (continuityBroken) {
                safeOffset.coerceAtMost(maxStepsPerHour)
            } else {
                val rawDelta = maxOf(0, deviceTotal - baseline)
                (rawDelta + safeOffset).coerceAtMost(maxStepsPerHour)
            }
        }

        /**
         * Resolve the device-total reference to subtract from when back-filling missed
         * hours. The device_total snapshot entering the missed window (latest snapshot at
         * or before [rangeStart]) is ground truth and immune to a corrupted/zeroed saved
         * total. Falls back to [savedDeviceTotal] only when no such snapshot exists.
         */
        fun resolveBackfillReferenceTotal(
            savedDeviceTotal: Int,
            snapshots: List<com.example.myhourlystepcounterv2.data.DeviceTotalSnapshot>,
            rangeStart: Long
        ): Int {
            val snapshotAtOrBeforeStart = snapshots
                .filter { it.timestamp <= rangeStart }
                .maxByOrNull { it.timestamp }
            return snapshotAtOrBeforeStart?.deviceTotal ?: savedDeviceTotal
        }

        /**
         * Guard against fabricating phantom steps during missed-hour backfill. The
         * reference must be a real positive total, monotonic with the current total, and
         * the implied closure delta cannot exceed the physical maximum across the missed
         * hours ([maxStepsPerHour] * [missedHourCount]). An implausible delta means the
         * reference is corrupt — the caller skips writes rather than storing the clamp.
         */
        fun isBackfillReferencePlausible(
            referenceTotal: Int,
            deviceTotalToUse: Int,
            missedHourCount: Int,
            maxStepsPerHour: Int
        ): Boolean {
            if (referenceTotal <= 0) return false
            if (deviceTotalToUse < referenceTotal) return false
            val maxPlausible = maxStepsPerHour.toLong() * maxOf(1, missedHourCount)
            return (deviceTotalToUse - referenceTotal) <= maxPlausible
        }

    }

    private val wakeLockMutex = Any()
    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeLockReferences = 0
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
    @Volatile private var hourlyGoal: Int = StepTrackerConfig.STEP_REMINDER_THRESHOLD
    private val notificationSyncing = MutableStateFlow(true)
    private data class TimelinePresentation(
        val statesExpanded: String,
        val statesCompact: String,
        val achievedHours: Int,
        val elapsedHours: Int
    )

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
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

        scope.launch {
            preferences.hourlyStepGoal.collect { hourlyGoal = it }
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
            val initialTimeline = buildTimelinePresentation(
                now = java.util.Calendar.getInstance(),
                dayHistory = emptyList(),
                currentHourSteps = 0,
                isSyncing = true
            )
            startForeground(
                NOTIFICATION_ID,
                buildNotification(0, 0, initialTimeline, isSyncing = true)
            )
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
                    // Keep TOTAL_STEPS_DEVICE fresh between hour boundaries. Without this
                    // it only updates at hour boundaries, leaving stale data that breaks
                    // pre-reboot offset recovery for mid-hour reboots (issue #7).
                    preferences.saveTotalStepsDevice(currentTotal)

                    // Feed checkpoint data back to notification pipeline when sensor events are stale.
                    // Without this, the notification shows 0 after reboot until the app is opened,
                    // because onSensorChanged() isn't called during doze/screen-off.
                    val checkpointSensorAge = System.currentTimeMillis() - sensorManager.getLastSensorEventTime()
                    val sensorStateNow = sensorManager.sensorState.value
                    if (shouldCheckpointUpdateNotificationWithOffset(
                            isInitialized = sensorStateNow.isInitialized,
                            sensorAgeMs = checkpointSensorAge,
                            currentTotal = currentTotal,
                            hourBaseline = sensorStateNow.lastHourStartStepCount,
                            preRebootOffset = sensorStateNow.preRebootOffset,
                            displayedSteps = sensorManager.currentStepCount.value
                        )
                    ) {
                        val estimate = computeDisplayedHourSteps(
                            currentTotal = currentTotal,
                            hourBaseline = sensorStateNow.lastHourStartStepCount,
                            preRebootOffset = sensorStateNow.preRebootOffset,
                            maxStepsPerHour = StepTrackerConfig.MAX_STEPS_PER_HOUR
                        )
                        android.util.Log.i(
                            "StepCounterFGSvc",
                            "Checkpoint: Updating stale notification from ${sensorManager.currentStepCount.value} to " +
                                "$estimate steps (offset=${sensorStateNow.preRebootOffset}, " +
                                "sensor ${checkpointSensorAge / 1000}s old)"
                        )
                        sensorManager.setLastKnownStepCount(currentTotal)
                    }
                }

                delay(CHECKPOINT_INTERVAL_MINUTES.minutes)
            }
        }

        // Observe flows and update the notification.
        // NOTE: No continuous wake lock is held here. When the device is asleep the
        // notification simply goes stale; it is refreshed on the next CPU wake (alarm,
        // sensor event delivery, screen-on) via this flow and the checkpoint loop.
        scope.launch {
            val currentHourCheckpointSteps = preferences.currentHourTimestamp
                .flatMapLatest { currentHourTimestamp ->
                    repository.getStepCountForHour(currentHourTimestamp)
                }

            combine(
                sensorManager.currentStepCount,
                preferences.currentHourTimestamp,
                currentHourCheckpointSteps,
                notificationSyncing
            ) { currentHourSteps, savedHourTimestamp, checkpointSteps, isSyncing ->
                android.util.Log.d("StepCounterFGSvc", "Live sensor: currentHourSteps=$currentHourSteps")

                // Use wall-clock hour for DB exclusion to prevent overcount when
                // saved currentHourTimestamp is stale (e.g. hour boundary not yet processed).
                // A stale saved timestamp would fail to exclude the checkpoint row for
                // the current hour, double-counting those steps.
                val now = java.util.Calendar.getInstance()
                val startOfDay = now.clone().let { it as java.util.Calendar
                    it.set(java.util.Calendar.HOUR_OF_DAY, 0)
                    it.set(java.util.Calendar.MINUTE, 0)
                    it.set(java.util.Calendar.SECOND, 0)
                    it.set(java.util.Calendar.MILLISECOND, 0)
                    it.timeInMillis
                }
                val wallClockHourTimestamp = now.apply {
                    set(java.util.Calendar.MINUTE, 0)
                    set(java.util.Calendar.SECOND, 0)
                    set(java.util.Calendar.MILLISECOND, 0)
                }.timeInMillis

                if (savedHourTimestamp > 0 && wallClockHourTimestamp != savedHourTimestamp) {
                    android.util.Log.w("StepCounterFGSvc",
                        "Notification daily query: using wall-clock hour ${java.util.Date(wallClockHourTimestamp)} " +
                            "instead of stale saved ${java.util.Date(savedHourTimestamp)}")
                }

                // Get daily total from database (excluding current hour by wall-clock)
                val checkpoint = checkpointSteps ?: 0
                val displayedCurrentHourSteps = maxOf(currentHourSteps, checkpoint)
                if (displayedCurrentHourSteps != currentHourSteps) {
                    android.util.Log.w(
                        "StepCounterFGSvc",
                        "Notification current hour checkpoint is ahead of live sensor: " +
                            "sensor=$currentHourSteps, checkpoint=$checkpoint. Displaying checkpointed value."
                    )
                }
                val dbTotal = repository.getTotalStepsForDayExcludingCurrentHour(startOfDay, wallClockHourTimestamp).first() ?: 0
                val dailyTotal = dbTotal + displayedCurrentHourSteps
                val dayHistory = repository.getStepsForDay(startOfDay, wallClockHourTimestamp).first()
                val timeline = buildTimelinePresentation(
                    now = java.util.Calendar.getInstance(),
                    dayHistory = dayHistory,
                    currentHourSteps = displayedCurrentHourSteps,
                    isSyncing = isSyncing
                )

                android.util.Log.d("StepCounterFGSvc", "Calculated: dbTotal=$dbTotal, currentHour=$displayedCurrentHourSteps, daily=$dailyTotal")
                StepNotificationState(
                    currentHourSteps = displayedCurrentHourSteps,
                    dailyTotal = dailyTotal,
                    isSyncing = isSyncing,
                    timeline = timeline
                )
            }
            .sample(3.seconds)  // THROTTLE: Only emit once every 3 seconds to prevent notification rate limiting
            .collect { state ->
                logTimestampStaleness()
                android.util.Log.d("StepCounterFGSvc", "Notification update (throttled 3s): currentHour=${state.currentHourSteps}, daily=${state.dailyTotal}, syncing=${state.isSyncing}")

                // Update notification with correct daily total
                val notification = buildNotification(
                    currentHourSteps = state.currentHourSteps,
                    totalSteps = state.dailyTotal,
                    timeline = state.timeline,
                    isSyncing = state.isSyncing
                )
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTIFICATION_ID, notification)
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

    private fun buildNotification(
        currentHourSteps: Int,
        totalSteps: Int,
        timeline: TimelinePresentation,
        isSyncing: Boolean = false
    ): Notification {
        val hourlyText = if (isSyncing) {
            getString(R.string.notification_title_syncing)
        } else {
            getString(R.string.notification_title_steps, currentHourSteps)
        }
        val dailyText = getString(R.string.notification_text_steps, totalSteps)
        val hitsText = getString(
            R.string.notification_hits_summary,
            timeline.achievedHours,
            timeline.elapsedHours
        )

        val openAppIntent = Intent(this, com.example.myhourlystepcounterv2.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val openAppPending = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val customView = android.widget.RemoteViews(packageName, R.layout.notification_persistent).apply {
            setTextViewText(R.id.notification_hourly, hourlyText)
            setTextViewText(R.id.notification_daily, dailyText)
            setTextViewText(R.id.notification_timeline, timeline.statesCompact)
            setTextViewText(R.id.notification_hits, hitsText)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(customView)
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
        val isSyncing: Boolean,
        val timeline: TimelinePresentation
    )

    private fun buildTimelinePresentation(
        now: java.util.Calendar,
        dayHistory: List<com.example.myhourlystepcounterv2.data.StepEntity>,
        currentHourSteps: Int,
        isSyncing: Boolean
    ): TimelinePresentation {
        val goal = hourlyGoal
        val currentHour = now.get(java.util.Calendar.HOUR_OF_DAY)
        val startOfDayCalendar = now.clone().let { it as java.util.Calendar }.apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val stepsByTimestamp = dayHistory.associate { it.timestamp to it.stepCount }
        val states = mutableListOf<String>()
        var achievedHours = 0

        // Sliding window: show up to MAX_TIMELINE_CIRCLES, ending at current hour + 1.
        // Lookahead (+1) only applies while there are still hours left inside the window.
        // Once we reach or pass ACTIVE_WINDOW_END_HOUR the active window is over —
        // show only completed past hours, no current/future circles.
        val windowEnd = when {
            currentHour < ACTIVE_WINDOW_START_HOUR -> ACTIVE_WINDOW_START_HOUR - 1  // empty range → no circles
            currentHour >= ACTIVE_WINDOW_END_HOUR -> ACTIVE_WINDOW_END_HOUR - 1     // window closed — show up to last active hour
            else -> minOf(currentHour + 1, ACTIVE_WINDOW_END_HOUR - 1)
        }
        val windowStart = maxOf(windowEnd - MAX_TIMELINE_CIRCLES + 1, ACTIVE_WINDOW_START_HOUR)

        for (hour in windowStart..windowEnd) {
            val hourTimestamp = (startOfDayCalendar.clone() as java.util.Calendar).apply {
                set(java.util.Calendar.HOUR_OF_DAY, hour)
            }.timeInMillis
            val symbol = when {
                hour < currentHour -> {
                    val hit = (stepsByTimestamp[hourTimestamp] ?: 0) >= goal
                    if (hit) {
                        achievedHours += 1
                        "🟢"
                    } else {
                        "❌"
                    }
                }
                hour == currentHour -> {
                    if (isSyncing) {
                        "⏳"
                    } else if (currentHourSteps >= goal) {
                        achievedHours += 1
                        "🟢"
                    } else {
                        "🟡"
                    }
                }
                else -> "⚪"
            }
            states.add(symbol)
        }

        val elapsedHours = when {
            currentHour < ACTIVE_WINDOW_START_HOUR -> 0
            currentHour >= ACTIVE_WINDOW_END_HOUR -> ACTIVE_WINDOW_END_HOUR - ACTIVE_WINDOW_START_HOUR
            else -> currentHour - windowStart + 1
        }

        return TimelinePresentation(
            statesExpanded = states.joinToString(" "),
            statesCompact = states.joinToString(""),
            achievedHours = achievedHours,
            elapsedHours = elapsedHours
        )
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

    /**
     * Acquire a short-lived partial wake lock for one unit of work (hour-boundary save,
     * missed-boundary backfill). The lock is reference-counted, so concurrent work items
     * share it and each matching [releaseShortWakeLock] releases one reference; it is
     * never torn down mid-work by a sibling coroutine. Each acquire also schedules an
     * auto-release after [WORK_WAKE_LOCK_TIMEOUT_MS], so a stalled coroutine can never
     * pin the CPU awake indefinitely. Between work items no lock is held, so the device
     * can deep-sleep normally.
     *
     * The wake-lock setting is read from DataStore at acquire time rather than from a
     * default-seeded cache, so an early boundary check honors the user's stored
     * preference even before the preference flow has emitted.
     */
    private suspend fun acquireShortWakeLock(reason: String) {
        val enabled = try {
            preferences.useWakeLock.first()
        } catch (e: Exception) {
            android.util.Log.w("StepCounterFGSvc", "Failed to read wake-lock preference ($reason)", e)
            false
        }
        if (!enabled) return

        synchronized(wakeLockMutex) {
            try {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                val lock = wakeLock ?: pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "myhourly:StepCounterWakeLock"
                ).apply { setReferenceCounted(false) }.also { wakeLock = it }

                if (wakeLockReferences == 0) {
                    lock.acquire()
                }
                wakeLockReferences++
                android.util.Log.i(
                    "StepCounterFGSvc",
                    "Work wake-lock acquired ($reason, refs=$wakeLockReferences)"
                )

                // Backstop: this reference can never hold the CPU awake longer than the timeout.
                scope.launch {
                    delay(WORK_WAKE_LOCK_TIMEOUT_MS)
                    releaseShortWakeLock("auto-timeout ($reason)")
                }
            } catch (e: Exception) {
                android.util.Log.w("StepCounterFGSvc", "Failed to acquire work wake-lock ($reason)", e)
            }
        }
    }

    /**
     * Release one reference of the short-lived work wake lock. The framework lock is only
     * released when the last reference goes away.
     */
    private fun releaseShortWakeLock(reason: String) {
        synchronized(wakeLockMutex) {
            val lock = wakeLock ?: return
            if (wakeLockReferences > 0) {
                wakeLockReferences--
            }
            if (wakeLockReferences == 0) {
                if (lock.isHeld) {
                    lock.release()
                    android.util.Log.d("StepCounterFGSvc", "Work wake-lock released ($reason)")
                }
                wakeLock = null
            } else {
                android.util.Log.d(
                    "StepCounterFGSvc",
                    "Work wake-lock reference released ($reason, refs=$wakeLockReferences)"
                )
            }
        }
    }

    /**
     * Release the work wake lock unconditionally (service stop/destroy). Any pending
     * auto-release coroutines are cancelled with the service scope.
     */
    private fun forceReleaseWakeLock() {
        synchronized(wakeLockMutex) {
            wakeLockReferences = 0
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
            wakeLock = null
        }
    }

    private fun stopForegroundService() {
        // Release work wake-lock if held
        forceReleaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Check if any hour boundaries were missed while the service was stopped.
     * This handles the case where user disabled permanent notification and later re-enabled it.
     */
    private suspend fun checkMissedHourBoundaries() {
        acquireShortWakeLock("missed-boundary check")
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

            val snapshots = preferences.getDeviceTotalSnapshots()
            // The device_total snapshot entering the missed window is the trustworthy
            // reference to subtract from — immune to a zeroed/stale saved total that
            // would otherwise make the whole lifetime count look like steps-while-closed.
            val referenceTotal = resolveBackfillReferenceTotal(
                savedDeviceTotal = validSavedDeviceTotal,
                snapshots = snapshots,
                rangeStart = rangeStart
            )
            val missedHourCount = ((rangeEnd - rangeStart) / (60 * 60 * 1000)).toInt() + 1
            val referencePlausible = isBackfillReferencePlausible(
                referenceTotal = referenceTotal,
                deviceTotalToUse = deviceTotalToUse,
                missedHourCount = missedHourCount,
                maxStepsPerHour = StepTrackerConfig.MAX_STEPS_PER_HOUR
            )

            val totalStepsWhileClosed = if (!continuityBroken && deviceTotalToUse > 0) {
                deviceTotalToUse - referenceTotal
            } else {
                0
            }
            if (totalStepsWhileClosed <= 0 || !referencePlausible) {
                android.util.Log.w(
                    "StepCounterFGSvc",
                    "Backfill: Skipping hour writes (totalStepsWhileClosed=$totalStepsWhileClosed, " +
                        "referenceTotal=$referenceTotal, deviceTotalToUse=$deviceTotalToUse, " +
                        "missedHourCount=$missedHourCount, plausible=$referencePlausible). " +
                        "Avoiding phantom steps from an untrustworthy reference total."
                )
            } else {
                val snapshotByHour = snapshots
                    .filter { it.timestamp in rangeStart until currentHourTimestamp }
                    .groupBy { ts ->
                        (ts.timestamp / (60 * 60 * 1000)) * (60 * 60 * 1000)
                    }
                    .mapValues { entry -> entry.value.maxByOrNull { it.timestamp }?.deviceTotal }

                val missingWithoutSnapshot = mutableListOf<Long>()
                var accountedSteps = 0
                var assignedSteps = 0
                var previousTotal = referenceTotal
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
                        // Backfill writes hourly rows for all missed hours including the
                        // saved hour, so any pre-reboot offset is now in DB. Clear it
                        // so it doesn't get re-added in the current hour.
                        preferences.saveCurrentHourPreRebootOffset(0)
                        android.util.Log.i(
                            "StepCounterFGSvc",
                            "Reset to current hour: baseline=$deviceTotalToUse, timestamp=$currentHourTimestamp, preRebootOffset cleared"
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
        } finally {
            releaseShortWakeLock("missed-boundary check")
        }
    }

    /**
     * Handle hour boundary: save completed hour and reset for new hour.
     * Extracted from HourBoundaryReceiver for reuse in foreground service.
     */
    private suspend fun handleHourBoundary() {
        acquireShortWakeLock("hour boundary")
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

            // Check for reboot or counter discontinuity before computing delta
            val savedBootCount = preferences.lastKnownBootCount.first()
            val currentBootCount = getCurrentBootCount()
            val rebootDetected = isDeviceRebootDetected(currentBootCount, savedBootCount)
            val continuityBroken = shouldBreakCounterContinuity(
                currentDeviceTotal = deviceTotal,
                savedDeviceTotal = previousHourStartStepCount,
                rebootDetected = rebootDetected
            )

            // Include any pre-reboot offset captured for the current hour. The offset
            // represents steps walked before the most recent reboot, which the sensor
            // counter (reset to 0 by reboot) cannot otherwise contribute.
            val preRebootOffset = preferences.currentHourPreRebootOffset.first()
            val stepsInPreviousHour = computeStepsForBoundarySave(
                deviceTotal = deviceTotal,
                baseline = previousHourStartStepCount,
                preRebootOffset = preRebootOffset,
                continuityBroken = continuityBroken,
                maxStepsPerHour = StepTrackerConfig.MAX_STEPS_PER_HOUR
            )
            if (continuityBroken) {
                android.util.Log.w(
                    "StepCounterFGSvc",
                    "handleHourBoundary: Counter continuity broken " +
                            "(device=$deviceTotal, baseline=$previousHourStartStepCount, reboot=$rebootDetected, offset=$preRebootOffset). " +
                            "Saving offset-only value $stepsInPreviousHour for previous hour."
                )
            } else if (preRebootOffset > 0) {
                android.util.Log.i(
                    "StepCounterFGSvc",
                    "handleHourBoundary: Including preRebootOffset=$preRebootOffset in hour save. " +
                        "Final stepsInPreviousHour=$stepsInPreviousHour"
                )
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
                    android.util.Log.w("StepCounterFGSvc", "Baseline already set in sensor, but still saving preferences")
                }

                // Update preferences with new hour baseline (always, even on duplicate sensor reset)
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

                // Clear pre-reboot offset: the hour it applied to has been saved.
                if (preRebootOffset > 0) {
                    preferences.saveCurrentHourPreRebootOffset(0)
                    // sensorManager.resetForNewHour above already cleared the in-memory copy
                }

                android.util.Log.i(
                    "StepCounterFGSvc",
                    "✓ Hour boundary processed: Saved $stepsInPreviousHour steps, reset to baseline=$deviceTotal, display=0, preRebootOffset cleared"
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
        } finally {
            releaseShortWakeLock("hour boundary")
        }
    }

    /**
     * Force an immediate update of the notification, bypassing the 3-second throttle.
     */
    private suspend fun updateNotificationImmediately() {
        try {
            val currentHourSteps = sensorManager.currentStepCount.first()

            // Use wall-clock hour for DB exclusion (same rationale as notification combine flow)
            val now = java.util.Calendar.getInstance()
            val startOfDay = now.clone().let { it as java.util.Calendar
                it.set(java.util.Calendar.HOUR_OF_DAY, 0)
                it.set(java.util.Calendar.MINUTE, 0)
                it.set(java.util.Calendar.SECOND, 0)
                it.set(java.util.Calendar.MILLISECOND, 0)
                it.timeInMillis
            }
            val wallClockHourTimestamp = now.apply {
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }.timeInMillis

            // Get daily total from database (excluding current hour by wall-clock)
            val checkpointSteps = repository.getStepForHour(wallClockHourTimestamp)?.stepCount ?: 0
            val displayedCurrentHourSteps = maxOf(currentHourSteps, checkpointSteps)
            if (displayedCurrentHourSteps != currentHourSteps) {
                android.util.Log.w(
                    "StepCounterFGSvc",
                    "Immediate notification checkpoint is ahead of live sensor: " +
                        "sensor=$currentHourSteps, checkpoint=$checkpointSteps. Displaying checkpointed value."
                )
            }
            val dbTotal = repository.getTotalStepsForDayExcludingCurrentHour(startOfDay, wallClockHourTimestamp).first() ?: 0
            val dailyTotal = dbTotal + displayedCurrentHourSteps
            val dayHistory = repository.getStepsForDay(startOfDay, wallClockHourTimestamp).first()
            val timeline = buildTimelinePresentation(
                now = java.util.Calendar.getInstance(),
                dayHistory = dayHistory,
                currentHourSteps = displayedCurrentHourSteps,
                isSyncing = notificationSyncing.value
            )

            android.util.Log.i("StepCounterFGSvc", "Forcing immediate notification update: hour=$displayedCurrentHourSteps, daily=$dailyTotal")

            val notification = buildNotification(displayedCurrentHourSteps, dailyTotal, timeline, notificationSyncing.value)
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
                    "Capturing pre-reboot in-hour steps as offset."
            )
            handleRebootRecovery(
                savedHourTimestamp = savedHourTimestamp,
                currentHourTimestamp = currentHourTimestamp,
                currentBootCount = currentBootCount
            )
            return
        }

        if (savedHourTimestamp == currentHourTimestamp) {
            // Same hour as last save — seed from saved baseline (ViewModel Branch 2)
            val baselineCandidate = preferences.hourStartStepCount.first()
            val savedTotal = preferences.totalStepsDevice.first()
            val currentDeviceSteps = sensorManager.getCurrentTotalSteps()
            val hasFreshSensorEvent = sensorManager.getLastSensorEventTime() > 0L

            // Restore any persisted pre-reboot offset (e.g., service was killed mid-hour
            // after a previous reboot, and the offset is still pending).
            val persistedOffset = preferences.currentHourPreRebootOffset.first()
            if (persistedOffset > 0) {
                sensorManager.setPreRebootOffset(persistedOffset)
                android.util.Log.i(
                    "StepCounterFGSvc",
                    "initializeSensorFromPreferences: Restored persisted preRebootOffset=$persistedOffset for current hour"
                )
            }

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
            // Different hour, no reboot — clear any stale offset (new hour starts fresh)
            val staleOffset = preferences.currentHourPreRebootOffset.first()
            if (staleOffset > 0) {
                preferences.saveCurrentHourPreRebootOffset(0)
                sensorManager.setPreRebootOffset(0)
                android.util.Log.i(
                    "StepCounterFGSvc",
                    "initializeSensorFromPreferences: Cleared stale preRebootOffset=$staleOffset (hour changed)"
                )
            }

            var currentDeviceSteps = sensorManager.getCurrentTotalSteps()

            // If sensor hasn't delivered an event yet, try fallback from preferences
            if (currentDeviceSteps <= 0) {
                val fallback = preferences.totalStepsDevice.first()
                if (fallback > 0) {
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

    /**
     * Recover from a device reboot. The Samsung device's TYPE_STEP_COUNTER resets to 0
     * on reboot, so pre-reboot in-hour steps cannot be re-derived from the sensor.
     * We capture them from the saved totalStepsDevice/hourStartStepCount as a "pre-reboot
     * offset" that gets added to every display/save calculation until the next hour
     * boundary clears it.
     *
     * Same-hour reboot: accumulate the offset and reset baseline to 0 for ongoing tracking.
     * Cross-hour reboot: write the saved hour's count to DB (atomic-keep-higher protects
     * any existing checkpoint) and start the new hour with no offset.
     */
    private suspend fun handleRebootRecovery(
        savedHourTimestamp: Long,
        currentHourTimestamp: Long,
        currentBootCount: Int
    ) {
        val savedBaseline = preferences.hourStartStepCount.first()
        val savedTotal = preferences.totalStepsDevice.first()
        val preRebootInHourSteps = computePreRebootInHourSteps(
            savedTotal = savedTotal,
            savedBaseline = savedBaseline,
            maxStepsPerHour = StepTrackerConfig.MAX_STEPS_PER_HOUR
        )
        val existingOffset = preferences.currentHourPreRebootOffset.first()

        if (savedHourTimestamp == currentHourTimestamp) {
            // Same-hour reboot: accumulate offset and reset baseline to 0
            val newOffset = accumulatePreRebootOffset(
                currentOffset = existingOffset,
                newInHourSteps = preRebootInHourSteps,
                maxStepsPerHour = StepTrackerConfig.MAX_STEPS_PER_HOUR
            )

            // Belt-and-braces: write to DB so notification recovers immediately even
            // if a code path misses the offset. saveHourlyStepsAtomic keeps the higher
            // value, so an existing checkpoint is preserved.
            if (newOffset > 0) {
                repository.saveHourlySteps(currentHourTimestamp, newOffset)
            }

            preferences.saveCurrentHourPreRebootOffset(newOffset)
            sensorManager.setPreRebootOffset(newOffset)

            // Set baseline to 0 so post-reboot sensor readings are treated as fresh
            // in-hour deltas. Don't touch lastKnownStepCount — let any sensor events
            // already received flow through (they're real post-reboot steps).
            sensorManager.setLastHourStartStepCount(0)
            sensorManager.markInitialized()

            // Persist the current sensor reading as the new totalStepsDevice so
            // subsequent restarts can compute the post-reboot delta correctly.
            val postRebootSensorValue = sensorManager.getCurrentTotalSteps().coerceAtLeast(0)
            preferences.saveHourData(
                hourStartStepCount = 0,
                currentTimestamp = currentHourTimestamp,
                totalSteps = postRebootSensorValue
            )
            if (currentBootCount > 0) {
                preferences.saveLastKnownBootCount(currentBootCount)
            }

            android.util.Log.i(
                "StepCounterFGSvc",
                "handleRebootRecovery (same hour): savedTotal=$savedTotal, savedBaseline=$savedBaseline, " +
                    "preRebootInHourSteps=$preRebootInHourSteps, existingOffset=$existingOffset → newOffset=$newOffset. " +
                    "Baseline reset to 0, sensor marked initialized."
            )
        } else {
            // Cross-hour reboot: write to saved hour's DB row to preserve pre-reboot count
            val combinedForSavedHour = accumulatePreRebootOffset(
                currentOffset = existingOffset,
                newInHourSteps = preRebootInHourSteps,
                maxStepsPerHour = StepTrackerConfig.MAX_STEPS_PER_HOUR
            )
            if (combinedForSavedHour > 0 && savedHourTimestamp > 0) {
                repository.saveHourlySteps(savedHourTimestamp, combinedForSavedHour)
                android.util.Log.i(
                    "StepCounterFGSvc",
                    "handleRebootRecovery (cross hour): Saved $combinedForSavedHour steps to " +
                        "${java.util.Date(savedHourTimestamp)} (preRebootInHourSteps=$preRebootInHourSteps, existingOffset=$existingOffset)"
                )
            }

            // New hour starts fresh
            preferences.saveCurrentHourPreRebootOffset(0)
            sensorManager.setPreRebootOffset(0)

            // Try to set up baseline for the new hour. Post-reboot the sensor is at 0,
            // so the baseline will be set lazily as the first sensor event arrives.
            val currentDeviceSteps = sensorManager.getCurrentTotalSteps()
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
                "handleRebootRecovery (cross hour): New hour ${java.util.Date(currentHourTimestamp)} initialized " +
                    "with baseline=$currentDeviceSteps"
            )
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
        val oneHourMs = 60 * 60 * 1000L
        if (savedHourTimestamp > 0 && driftMs >= oneHourMs) {
            val now = System.currentTimeMillis()
            if (now - lastStalenessLogTime > oneHourMs) {
                lastStalenessLogTime = now
                android.util.Log.e(
                    "StepCounterFGSvc",
                    "Stale currentHourTimestamp detected while service running: " +
                        "saved=${java.util.Date(savedHourTimestamp)} current=${java.util.Date(currentHourTimestamp)} " +
                        "driftHours=${driftMs / oneHourMs}"
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
        // Release work wake-lock if held
        forceReleaseWakeLock()
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

        val preRebootOffset = preferences.currentHourPreRebootOffset.first()
        val clampedSteps = computeStepsForBoundarySave(
            deviceTotal = currentDeviceTotal,
            baseline = baseline,
            preRebootOffset = preRebootOffset,
            continuityBroken = false,
            maxStepsPerHour = StepTrackerConfig.MAX_STEPS_PER_HOUR
        )
        repository.saveHourlySteps(currentHourTimestamp, clampedSteps)
        android.util.Log.d(
            "StepCounterFGSvc",
            "Checkpoint saved for ${java.util.Date(currentHourTimestamp)}: steps=$clampedSteps (delta=${currentDeviceTotal - baseline}, offset=$preRebootOffset)"
        )
    }
}
