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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.minutes
import com.example.myhourlystepcounterv2.R
import com.example.myhourlystepcounterv2.data.StepPreferences
import com.example.myhourlystepcounterv2.StepTrackerConfig
import com.example.myhourlystepcounterv2.PermissionHelper
import com.example.myhourlystepcounterv2.getCurrentBootCount
import com.example.myhourlystepcounterv2.resolveKnownTotalForInitialization
import com.example.myhourlystepcounterv2.wallClockHourTimestamp
import com.example.myhourlystepcounterv2.wallClockStartOfDay

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

        /**
         * Whether a missed-boundary check is really looking at the ordinary hourly switch and
         * should hand off to the boundary handler.
         *
         * A saved hour timestamp exactly one hour behind the current hour is no gap at all —
         * it is the hour that just completed. Backfill cannot close it correctly: it still
         * carries the in-progress checkpoint row written mid-hour, and without a device-total
         * snapshot bracketing the hour's tail [resolveBackfillHourSteps] cannot raise that
         * row, so the stale value stands and the boundary is marked processed anyway. The
         * boundary handler recomputes the hour from its baseline instead.
         *
         * Deliberately NOT gated on the sensor having reported in this process, because
         * `lastSensorEventTimeMs` is written only by [StepSensorManager.onSensorChanged] while
         * `markInitialized()` seeds `isInitialized` from DataStore — so requiring a delivered
         * event would refuse the hand-off whenever the first callback has not landed yet. The
         * handler does its own FIFO flush, falls back to the saved device total, and runs
         * [shouldBreakCounterContinuity] before trusting anything.
         *
         * FORMERLY A KNOWN GAP (issue #25) — this predicate alone did not rescue a cold start:
         * both `StepCounterForegroundService.initializeSensorFromPreferences()`'s different-hour
         * branch (an OS-restarted service) and `StepCounterViewModel.initialize()`'s equivalent
         * branch (a launcher-tap cold start) used to call `saveHourData()`/seed the new hour
         * directly before any missed-boundary check ever ran, so the completed hour kept its
         * partial checkpoint row and its tail steps were absorbed into the new baseline. Both
         * now go through `HourBoundaryCloser.checkMissedHourBoundariesLocked()` (which reaches
         * this predicate) before seeding, coordinated by `HourBoundaryCloser.mutex` so whichever
         * cold start runs first is the one that closes the completed hour.
         *
         * What it does require: a counter to work from at all — either source, since the
         * handler falls back to the saved total — and no reboot. After a reboot the sensor
         * counter has reset to 0 while the saved total is a large pre-reboot value, so the
         * handler would set a wildly wrong hour baseline; that case belongs to the backfill
         * path's own post-reboot guards.
         */
        fun shouldDelegateOrdinaryHourTransition(
            hoursDifference: Long,
            currentDeviceTotal: Int,
            savedDeviceTotal: Int,
            rebootDetected: Boolean
        ): Boolean {
            if (hoursDifference != 1L) return false
            if (rebootDetected) return false
            return maxOf(currentDeviceTotal, savedDeviceTotal) > 0
        }

        /** What a missed-boundary check should do about the hour it is looking at. */
        enum class BoundaryAction {
            /** Nothing to close: already processed, no saved hour, or no gap yet. */
            NONE,

            /** The ordinary hourly switch — [shouldDelegateOrdinaryHourTransition]. */
            DELEGATE_TO_HANDLER,

            /**
             * Run the backfill: a real gap of missed boundaries, or a single-hour gap the
             * hand-off refused (reboot, or no usable counter from either source).
             */
            BACKFILL
        }

        /**
         * The single decision a missed-boundary check makes, kept whole and pure so the
         * ordering is testable: dedupe first, then the saved-hour and gap validity checks,
         * then the ordinary-switch hand-off, and only then backfill. Delegation is decided
         * here — before the caller claims a backfill range — so a hand-off can never consume
         * a range claim, and the arms cannot be reordered without this decision table failing.
         *
         * A [BoundaryAction.BACKFILL] result always implies a non-empty range: it is only
         * reachable with `hoursDifference >= 1`, which by integer division means
         * `savedHourTimestamp <= currentHourTimestamp - 1h`, i.e. `rangeEnd >= rangeStart`.
         */
        fun resolveBoundaryAction(
            currentHourTimestamp: Long,
            savedHourTimestamp: Long,
            effectiveLastProcessed: Long,
            currentDeviceTotal: Int,
            savedDeviceTotal: Int,
            rebootDetected: Boolean
        ): BoundaryAction {
            if (currentHourTimestamp <= effectiveLastProcessed) return BoundaryAction.NONE
            if (savedHourTimestamp <= 0 || savedHourTimestamp >= currentHourTimestamp) {
                return BoundaryAction.NONE
            }
            val hoursDifference = (currentHourTimestamp - savedHourTimestamp) / (60 * 60 * 1000)
            if (hoursDifference <= 0) return BoundaryAction.NONE
            return if (shouldDelegateOrdinaryHourTransition(
                    hoursDifference = hoursDifference,
                    currentDeviceTotal = currentDeviceTotal,
                    savedDeviceTotal = savedDeviceTotal,
                    rebootDetected = rebootDetected
                )
            ) {
                BoundaryAction.DELEGATE_TO_HANDLER
            } else {
                BoundaryAction.BACKFILL
            }
        }

        /**
         * Reconcile the recomputed hour total with the in-hour count the user was actually
         * shown (persistent notification, goal-achieved alert). The displayed count is
         * monotonic and includes the pre-reboot offset, so it can legitimately sit above a
         * bare device-total delta; persisting the lower value is what makes a timeline
         * marker contradict the "goal achieved" notification for the same hour. When counter
         * continuity is broken the displayed value is not trustworthy (post-reboot counter,
         * adjusted baseline), so the computed value stands on its own.
         */
        fun reconcileBoundarySaveWithDisplay(
            computedSteps: Int,
            displayedSteps: Int,
            continuityBroken: Boolean,
            maxStepsPerHour: Int
        ): Int {
            val safeComputed = computedSteps.coerceIn(0, maxStepsPerHour)
            if (continuityBroken) return safeComputed
            return maxOf(safeComputed, maxOf(0, displayedSteps)).coerceAtMost(maxStepsPerHour)
        }

        /**
         * Steps to write for one hour of a missed-boundary backfill, or null to leave the
         * stored row alone. A device-total snapshot inside the hour brackets it against
         * [previousTotal], so its delta is measured; an existing row may be a partial
         * in-progress checkpoint, so it only stands when it is already the higher of the two.
         */
        fun resolveBackfillHourSteps(
            existingSteps: Int?,
            snapshotTotal: Int?,
            previousTotal: Int,
            maxStepsPerHour: Int
        ): Int? {
            if (snapshotTotal == null || snapshotTotal < previousTotal) return null
            val measured = (snapshotTotal - previousTotal).coerceIn(0, maxStepsPerHour)
            if (existingSteps != null && existingSteps >= measured) return null
            return measured
        }

        /**
         * Whether a cold-start init path's seed-only fallback must still run after it already
         * tried to close the previous hour via `HourBoundaryCloser.checkMissedHourBoundariesLocked`
         * (used identically by both `StepCounterForegroundService` and `StepCounterViewModel` —
         * issue #25). That attempt can resolve to [BoundaryAction.NONE] (already marked
         * processed — narrower now that the processed marker is written only after the hour's
         * row and the new baseline are both durable) or a [BoundaryAction.BACKFILL] with no
         * usable device total, either of which leaves the saved hour timestamp unadvanced. The
         * sensor still needs to end up initialized one way or another, so the caller falls back
         * to a plain seed whenever the close left the timestamp behind.
         */
        fun needsColdStartSeedFallback(
            hourTimestampAfterClose: Long,
            currentHourTimestamp: Long
        ): Boolean = hourTimestampAfterClose != currentHourTimestamp

    }

    private var wakeLock: PowerManager.WakeLock? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val wakeLockLedger = WorkWakeLockLedger(
        scope = scope,
        timeoutMs = WORK_WAKE_LOCK_TIMEOUT_MS,
        onFirstAcquire = {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val lock = wakeLock ?: pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "myhourly:StepCounterWakeLock"
            ).apply { setReferenceCounted(false) }.also { wakeLock = it }
            if (!lock.isHeld) lock.acquire()
        },
        onLastRelease = {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
        },
        onTimeout = { reason ->
            android.util.Log.w(
                "StepCounterFGSvc",
                "Work wake-lock reference timed out after ${WORK_WAKE_LOCK_TIMEOUT_MS / 1000}s ($reason)"
            )
        }
    )
    private lateinit var sensorManager: com.example.myhourlystepcounterv2.sensor.StepSensorManager
    private lateinit var preferences: StepPreferences
    private lateinit var repository: com.example.myhourlystepcounterv2.data.StepRepository
    private val hourBoundaryLoopRunner = HourBoundaryLoopRunner()

    /**
     * Closes a completed hour and seeds the new one. Shared with
     * [com.example.myhourlystepcounterv2.ui.StepCounterViewModel]'s cold-start path via
     * [HourBoundaryCloser.mutex] — see that class's KDoc for why (issue #25 part 2).
     */
    private lateinit var hourBoundaryCloser: HourBoundaryCloser

    // Health check variables for hour boundary loop
    private var lastSuccessfulHourBoundary: Long = 0
    private var consecutiveFailures: Int = 0
    @Volatile private var hourBoundaryLoopActive: Boolean = false
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

    private data class NotificationInputs(
        val currentHourSteps: Int,
        val savedHourTimestamp: Long,
        val checkpointSteps: Int?,
        val isSyncing: Boolean
    )

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        preferences = StepPreferences(applicationContext)
        val database = com.example.myhourlystepcounterv2.data.StepDatabase.getDatabase(applicationContext)
        repository = com.example.myhourlystepcounterv2.data.StepRepository(
            stepDao = database.stepDao(),
            anomalyDao = database.stepAnomalyDao(),
            snapshotProvider = { preferences.getDeviceTotalSnapshots() },
            sourcePathRecorder = { hour, path -> preferences.saveHourSourcePath(hour, path) },
            sourcePathReader = { preferences.getHourSourcePaths() }
        )
        scope.launch {
            val bootCount = getCurrentBootCount(contentResolver)
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

        hourBoundaryCloser = HourBoundaryCloser(
            preferences = preferences,
            sensorManager = sensorManager,
            repository = repository,
            getCurrentBootCount = { getCurrentBootCount(contentResolver) },
            acquireWakeLock = { reason -> acquireShortWakeLock(reason) },
            releaseWakeLock = { token, reason -> releaseShortWakeLock(token, reason) },
            onNotificationRefresh = { updateNotificationImmediately() },
            onAlarmReschedule = {
                com.example.myhourlystepcounterv2.notifications.AlarmScheduler.scheduleHourBoundaryAlarms(applicationContext)
                android.util.Log.d("StepCounterFGSvc", "Rescheduled backup alarm for next hour")
                com.example.myhourlystepcounterv2.notifications.AlarmScheduler.scheduleBoundaryCheckAlarm(applicationContext)
                android.util.Log.d("StepCounterFGSvc", "Rescheduled boundary check alarm")
            },
            logTag = "StepCounterFGSvc"
        )

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

        // If the OS killed the process and restarted for this service (without UI),
        // the sensor singleton will be recreated with isInitialized=false.
        // Seed it from saved preferences so currentStepCount emits correct values.
        // Launched only after startForeground succeeds: this path can close the previous
        // hour (issue #25) via HourBoundaryCloser.mutex-guarded logic, and a scope.cancel()
        // racing with that close could mark a boundary processed without ever saving its row.
        if (!sensorManager.sensorState.value.isInitialized) {
            scope.launch {
                try {
                    HourBoundaryCloser.mutex.withLock { initializeSensorFromPreferences() }
                } catch (e: Exception) {
                    android.util.Log.e("StepCounterFGSvc", "Error initializing sensor from preferences", e)
                }
            }
        } else {
            android.util.Log.d("StepCounterFGSvc", "Sensor already initialized (ViewModel active), skipping service-side init")
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
                NotificationInputs(currentHourSteps, savedHourTimestamp, checkpointSteps, isSyncing)
            }
            .sample(3.seconds)  // THROTTLE cheap inputs first so expensive Room queries run at most once per 3s
            .map { inputs ->
                // Use wall-clock hour for DB exclusion to prevent overcount when
                // saved currentHourTimestamp is stale (e.g. hour boundary not yet processed).
                // A stale saved timestamp would fail to exclude the checkpoint row for
                // the current hour, double-counting those steps.
                // Single clock read shared by both derivations below: two independent
                // System.currentTimeMillis() calls could straddle midnight and disagree on
                // which day startOfDay and wallClockHourTimestamp belong to.
                val now = System.currentTimeMillis()
                val startOfDay = wallClockStartOfDay(now)
                val wallClockHourTimestamp = wallClockHourTimestamp(now)

                if (inputs.savedHourTimestamp > 0 && wallClockHourTimestamp != inputs.savedHourTimestamp) {
                    android.util.Log.w("StepCounterFGSvc",
                        "Notification daily query: using wall-clock hour ${java.util.Date(wallClockHourTimestamp)} " +
                            "instead of stale saved ${java.util.Date(inputs.savedHourTimestamp)}")
                }

                // Get daily total from database (excluding current hour by wall-clock)
                val checkpoint = inputs.checkpointSteps ?: 0
                val displayedCurrentHourSteps = maxOf(inputs.currentHourSteps, checkpoint)
                if (displayedCurrentHourSteps != inputs.currentHourSteps) {
                    android.util.Log.w(
                        "StepCounterFGSvc",
                        "Notification current hour checkpoint is ahead of live sensor: " +
                            "sensor=${inputs.currentHourSteps}, checkpoint=$checkpoint. Displaying checkpointed value."
                    )
                }
                val dbTotal = repository.getTotalStepsForDayExcludingCurrentHour(startOfDay, wallClockHourTimestamp).first() ?: 0
                val dailyTotal = dbTotal + displayedCurrentHourSteps
                val dayHistory = repository.getStepsForDay(startOfDay, wallClockHourTimestamp).first()
                val timeline = buildTimelinePresentation(
                    now = java.util.Calendar.getInstance(),
                    dayHistory = dayHistory,
                    currentHourSteps = displayedCurrentHourSteps,
                    isSyncing = inputs.isSyncing
                )

                StepNotificationState(
                    currentHourSteps = displayedCurrentHourSteps,
                    dailyTotal = dailyTotal,
                    isSyncing = inputs.isSyncing,
                    timeline = timeline
                )
            }
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
                hourBoundaryCloser.checkMissedHourBoundaries()
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
     * Acquire a reference on the short-lived work wake lock for one unit of work
     * (hour-boundary save, missed-boundary backfill), and return the token identifying it.
     * The caller must pass that token — and only that token — to [releaseShortWakeLock] when
     * the work finishes, normally in a `finally`. Returns null when the user has the setting
     * off, the lock could not be taken, or the service is tearing down; [releaseShortWakeLock]
     * then does nothing and the work simply runs without a lock.
     *
     * Concurrent work items share one framework lock via [WorkWakeLockLedger]: it is taken on
     * the first outstanding reference and dropped when the last one retires, so a sibling can
     * never release a lock it is still working under. Each reference expires on its own clock
     * ([WORK_WAKE_LOCK_TIMEOUT_MS] from its own acquire), so a stalled coroutine cannot pin the
     * CPU awake indefinitely and a healthy sibling is never torn down early by someone else's
     * timer. Between work items no lock is held, so the device can deep-sleep normally.
     *
     * The wake-lock setting is read from DataStore at acquire time rather than from a
     * default-seeded cache, so an early boundary check honors the user's stored
     * preference even before the preference flow has emitted.
     */
    private suspend fun acquireShortWakeLock(reason: String): Long? {
        val enabled = try {
            preferences.useWakeLock.first()
        } catch (e: Exception) {
            android.util.Log.w("StepCounterFGSvc", "Failed to read wake-lock preference ($reason)", e)
            false
        }
        if (!enabled) return null

        return try {
            val token = wakeLockLedger.acquire(reason)
            if (token == null) {
                android.util.Log.w(
                    "StepCounterFGSvc",
                    "Work wake-lock refused, service scope is shutting down ($reason)"
                )
            } else {
                android.util.Log.i(
                    "StepCounterFGSvc",
                    "Work wake-lock acquired ($reason, refs=${wakeLockLedger.referenceCount})"
                )
            }
            token
        } catch (e: Exception) {
            android.util.Log.w("StepCounterFGSvc", "Failed to acquire work wake-lock ($reason)", e)
            null
        }
    }

    /**
     * Retire the wake-lock reference identified by [token] — the one this work item took from
     * [acquireShortWakeLock]. The framework lock is only released once the last outstanding
     * reference is gone. A null token (setting off, acquire failed) or a token already retired
     * by its own timeout is a no-op, so this can never touch another work item's reference.
     */
    private fun releaseShortWakeLock(token: Long?, reason: String) {
        if (token == null) return
        wakeLockLedger.release(token)
        android.util.Log.d(
            "StepCounterFGSvc",
            "Work wake-lock reference released ($reason, refs=${wakeLockLedger.referenceCount})"
        )
    }

    /**
     * Release the work wake lock and every outstanding reference unconditionally
     * (service stop/destroy), cancelling the pending auto-release backstops.
     */
    private fun forceReleaseWakeLock() {
        wakeLockLedger.releaseAll()
    }

    private fun stopForegroundService() {
        // Release work wake-lock if held
        forceReleaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Force an immediate update of the notification, bypassing the 3-second throttle.
     */
    private suspend fun updateNotificationImmediately() {
        try {
            val currentHourSteps = sensorManager.currentStepCount.first()

            // Use wall-clock hour for DB exclusion (same rationale as notification combine flow).
            // Single clock read shared by both derivations, same reasoning as there.
            val now = System.currentTimeMillis()
            val startOfDay = wallClockStartOfDay(now)
            val wallClockHourTimestamp = wallClockHourTimestamp(now)

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
        val currentBootCount = getCurrentBootCount(contentResolver)
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
            // Different hour, no reboot. This cold start (an OS-restarted service) usually
            // beats the boundary loop's timer and the alarm-driven check to the hour
            // timestamp, so it used to be a place that would silently discard the completed
            // hour's tail steps by seeding straight into the new hour (issue #25). Delegate
            // through the shared HourBoundaryCloser (used identically from the ViewModel's
            // own launcher-tap cold start — see its KDoc) so whichever cold start gets here
            // first closes the completed hour with its real total instead of losing it.
            hourBoundaryCloser.checkMissedHourBoundariesLocked()

            val hourTimestampAfterClose = preferences.currentHourTimestamp.first()
            if (!needsColdStartSeedFallback(hourTimestampAfterClose, currentHourTimestamp)) {
                // The close advanced the saved hour to now — baseline and timestamp are
                // already correct, the sensor just needs to be flipped into service.
                sensorManager.markInitialized()
                android.util.Log.i(
                    "StepCounterFGSvc",
                    "initializeSensorFromPreferences: Completed hour closed via missed-boundary handling; " +
                            "sensor marked initialized for new hour ${java.util.Date(hourTimestampAfterClose)}"
                )
                return
            }

            // The close attempt left the saved hour unadvanced — already marked processed
            // by a prior crashed attempt, or no usable device total to reset with. The
            // sensor still has to be initialized one way or another, so fall back to a
            // plain seed exactly as before this delegation was added.
            android.util.Log.w(
                "StepCounterFGSvc",
                "initializeSensorFromPreferences: Missed-boundary close did not advance the hour " +
                        "(still ${java.util.Date(hourTimestampAfterClose)}). Falling back to seed-only."
            )

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
                repository.saveHourlySteps(currentHourTimestamp, newOffset, sourcePath = "preRebootOffset")
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
                repository.saveHourlySteps(savedHourTimestamp, combinedForSavedHour, sourcePath = "rebootRecovery")
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
            checkMissed = { hourBoundaryCloser.checkMissedHourBoundaries() },
            handleBoundary = {
            hourBoundaryCloser.handleHourBoundary()
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

                // The hour just closed cannot be judged yet, but the one before it now can:
                // the ledger has moved past that boundary. Idempotent, never touches steps,
                // and off the boundary path so a slow sweep cannot delay the next hour.
                scope.launch { repository.sweepForAnomalies() }
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
        repository.saveHourlySteps(currentHourTimestamp, clampedSteps, sourcePath = "checkpoint")
        android.util.Log.d(
            "StepCounterFGSvc",
            "Checkpoint saved for ${java.util.Date(currentHourTimestamp)}: steps=$clampedSteps (delta=${currentDeviceTotal - baseline}, offset=$preRebootOffset)"
        )
    }
}
