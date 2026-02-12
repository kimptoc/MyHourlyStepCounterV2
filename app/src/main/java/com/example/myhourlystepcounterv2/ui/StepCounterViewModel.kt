@file:OptIn(ExperimentalCoroutinesApi::class)
package com.example.myhourlystepcounterv2.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myhourlystepcounterv2.PermissionHelper
import com.example.myhourlystepcounterv2.StepTrackerConfig
import com.example.myhourlystepcounterv2.data.StepDatabase
import com.example.myhourlystepcounterv2.data.StepEntity
import com.example.myhourlystepcounterv2.data.StepPreferences
import com.example.myhourlystepcounterv2.data.StepRepository
import com.example.myhourlystepcounterv2.resolveKnownTotalForInitialization
import com.example.myhourlystepcounterv2.sensor.StepSensorManager
import com.example.myhourlystepcounterv2.worker.WorkManagerScheduler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.util.Calendar

class StepCounterViewModel(private val repository: StepRepository) : ViewModel() {
    private lateinit var sensorManager: StepSensorManager
    private lateinit var preferences: StepPreferences
    private val uiDbWritesEnabled = false
    private val sensorSyncTimeoutMs = 15_000L
    private val freshEventWindowMs = 5_000L
    @Volatile private var syncProbeStartMs: Long = 0L

    companion object {
        fun shouldSyncTimestamp(
            sensorInitialized: Boolean,
            sensorBaseline: Int,
            sensorCurrentSteps: Int,
            maxStepsPerHour: Int
        ): Boolean {
            return sensorInitialized &&
                sensorBaseline > 0 &&
                sensorCurrentSteps in 0..maxStepsPerHour
        }

        fun isSensorReadingFresh(
            nowMs: Long,
            lastEventTimeMs: Long,
            freshEventWindowMs: Long
        ): Boolean {
            return lastEventTimeMs > 0 && nowMs - lastEventTimeMs <= freshEventWindowMs
        }

        fun shouldStopSyncingFromCallback(
            isSyncing: Boolean,
            syncProbeStartMs: Long,
            lastEventTimeMs: Long
        ): Boolean {
            return isSyncing && syncProbeStartMs > 0L && lastEventTimeMs > syncProbeStartMs
        }

    }

    // Guard to prevent duplicate initialization
    @Volatile
    private var isInitialized = false

    private val _hourlySteps = MutableStateFlow(0)
    val hourlySteps: StateFlow<Int> = _hourlySteps.asStateFlow()

    private val _isSyncing = MutableStateFlow(true)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _dailySteps = MutableStateFlow(0)
    val dailySteps: StateFlow<Int> = _dailySteps.asStateFlow()

    private val _dayHistory = MutableStateFlow<List<StepEntity>>(emptyList())
    val dayHistory: StateFlow<List<StepEntity>> = _dayHistory.asStateFlow()

    private val _currentTime = MutableStateFlow(System.currentTimeMillis())
    val currentTime: StateFlow<Long> = _currentTime.asStateFlow()

    /**
     * Initialize the view model with context-dependent components.
     * IMPORTANT: Must be called with context.applicationContext (not Activity context)
     * to avoid context leaks in long-lived objects (sensor manager, preferences, WorkManager).
     */
    fun initialize(context: Context) {
        // Guard against multiple initialize() calls with atomic check-and-set
        synchronized(this) {
            if (isInitialized) {
                android.util.Log.w("StepCounter", "initialize() called but already initialized - ignoring duplicate call")
                return
            }
            isInitialized = true
        }
        android.util.Log.d("StepCounter", "initialize() starting...")

        sensorManager = StepSensorManager.getInstance(context)
        preferences = StepPreferences(context)

        // Check permission before registering sensor listener
        if (PermissionHelper.hasActivityRecognitionPermission(context)) {
            sensorManager.startListening()
            android.util.Log.d("StepCounter", "Sensor listener started - ACTIVITY_RECOGNITION permission granted")
            startSensorSyncProbe("initialize")
        } else {
            android.util.Log.w("StepCounter", "Sensor listener NOT started - ACTIVITY_RECOGNITION permission denied")
            _isSyncing.value = false
        }

        // Schedule the hourly work
        WorkManagerScheduler.scheduleHourlyStepCounter(context)

        // Schedule the hour boundary check work as a backup
        WorkManagerScheduler.scheduleHourBoundaryCheck(context)

        // Initialize sensor manager with current sensor reading
        viewModelScope.launch {
            // Wait for sensor to provide actual reading with extended timeout
            // Some devices may take longer than 500ms for the sensor to fire
            var actualDeviceSteps = 0
            val maxRetries = 10  // 10 retries × 100ms = ~1 second timeout
            var sensorReadObtained = false

            for (i in 0 until maxRetries) {
                actualDeviceSteps = sensorManager.getCurrentTotalSteps()
                if (actualDeviceSteps > 0) {
                    sensorReadObtained = true
                    android.util.Log.d(
                        "StepCounter",
                        "App startup: Sensor responded on retry $i with $actualDeviceSteps steps"
                    )
                    break
                }
                if (i < maxRetries - 1) {
                    delay(100)
                }
            }

            // If sensor didn't respond in time, fall back to last known value from preferences
            if (!sensorReadObtained || actualDeviceSteps == 0) {
                val fallbackValue = preferences.totalStepsDevice.first()
                if (fallbackValue > 0) {
                    actualDeviceSteps = fallbackValue
                    android.util.Log.w(
                        "StepCounter",
                        "App startup: Sensor didn't respond within ${maxRetries * 100}ms. " +
                                "Using fallback value from preferences: $fallbackValue"
                    )
                } else {
                    android.util.Log.w(
                        "StepCounter",
                        "App startup: Sensor unresponsive and no fallback value available. " +
                                "Starting fresh with 0. This is normal for first launch."
                    )
                }
            }

            android.util.Log.d("StepCounter", "App startup: Using device steps = $actualDeviceSteps")

            if (actualDeviceSteps >= 0) {
                val savedHourTimestamp = preferences.currentHourTimestamp.first()
                val currentHourTimestamp = Calendar.getInstance().apply {
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                val currentStartOfDay = getStartOfDay()
                val sensorState = sensorManager.sensorState.value

                when {
                    sensorState.isInitialized && shouldSyncTimestamp(
                        sensorInitialized = sensorState.isInitialized,
                        sensorBaseline = sensorState.lastHourStartStepCount,
                        sensorCurrentSteps = sensorState.currentHourSteps,
                        maxStepsPerHour = StepTrackerConfig.MAX_STEPS_PER_HOUR
                    ) -> {
                        if (currentHourTimestamp != savedHourTimestamp) {
                            preferences.saveCurrentHourTimestamp(currentHourTimestamp)
                            android.util.Log.i(
                                "StepCounter",
                                "FG service tracking - synced timestamp only (hour=$currentHourTimestamp)"
                            )
                        }
                    }

                    !sensorState.isInitialized && savedHourTimestamp == currentHourTimestamp -> {
                        val baselineCandidate = preferences.hourStartStepCount.first()
                        val baseline = if (baselineCandidate > 0) baselineCandidate else actualDeviceSteps
                        val savedTotal = preferences.totalStepsDevice.first()
                        val hasFreshSensorEvent = sensorManager.getLastSensorEventTime() > 0L
                        val knownTotal = resolveKnownTotalForInitialization(
                            savedTotal = savedTotal,
                            baseline = baseline,
                            currentDeviceSteps = actualDeviceSteps,
                            hasFreshSensorEvent = hasFreshSensorEvent
                        )

                        sensorManager.setLastHourStartStepCount(baseline)
                        sensorManager.setLastKnownStepCount(knownTotal)
                        sensorManager.markInitialized()
                        preferences.saveCurrentHourTimestamp(currentHourTimestamp)
                        android.util.Log.i(
                            "StepCounter",
                            "FG service had fresh prefs - seeded sensor from saved baseline=$baseline, total=$knownTotal"
                        )
                    }

                    else -> {
                        android.util.Log.w(
                            "StepCounter",
                            "Cold start or stale prefs detected (saved=$savedHourTimestamp, current=$currentHourTimestamp). " +
                                    "Seeding from current device steps=$actualDeviceSteps"
                        )
                        preferences.saveHourData(
                            hourStartStepCount = actualDeviceSteps,
                            currentTimestamp = currentHourTimestamp,
                            totalSteps = actualDeviceSteps
                        )
                        sensorManager.setLastHourStartStepCount(actualDeviceSteps)
                        sensorManager.setLastKnownStepCount(actualDeviceSteps)
                        sensorManager.markInitialized()
                    }
                }

                preferences.saveLastOpenDate(currentStartOfDay)
            }
        }

        // Observe sensor step count
        viewModelScope.launch {
            sensorManager.currentStepCount.collect { steps ->
                _hourlySteps.value = steps
                val lastEventTime = sensorManager.getLastSensorEventTime()

                if (shouldStopSyncingFromCallback(_isSyncing.value, syncProbeStartMs, lastEventTime)) {
                    _isSyncing.value = false
                    android.util.Log.i(
                        "StepCounter",
                        "Sensor sync completed via callback: eventTime=$lastEventTime, probeStart=$syncProbeStartMs"
                    )
                }

                // Save current device steps to preferences with monotonic check
                val newDeviceTotal = sensorManager.getCurrentTotalSteps()
                val previousDeviceTotal = preferences.totalStepsDevice.first()

                // Only save if value increased or stayed same (monotonic check)
                // This prevents sensor resets from corrupting the cached baseline
                if (newDeviceTotal >= previousDeviceTotal) {
                    preferences.saveTotalStepsDevice(newDeviceTotal)
                } else {
                    android.util.Log.w(
                        "StepCounter",
                        "⚠️ BLOCKED preferences save: Device total decreased from $previousDeviceTotal to $newDeviceTotal. " +
                                "Sensor reset in progress - keeping previous cached value."
                    )
                }
            }
        }

        // Observe daily steps (database + current hour)
        viewModelScope.launch {
            combine(
                preferences.lastStartOfDay,
                preferences.currentHourTimestamp,
                sensorManager.currentStepCount
            ) { storedStartOfDay, currentHourTimestamp, currentHourSteps ->
                val effectiveStartOfDay = if (storedStartOfDay > 0) storedStartOfDay else getStartOfDay()
                Triple(effectiveStartOfDay, currentHourTimestamp, currentHourSteps)
            }.flatMapLatest { (effectiveStartOfDay, currentHourTimestamp, currentHourSteps) ->
                // Use new query that excludes current hour to avoid double-counting
                repository.getTotalStepsForDayExcludingCurrentHour(effectiveStartOfDay, currentHourTimestamp)
                    .combine(flowOf(currentHourSteps)) { dbTotal, hourSteps ->
                        val finalTotal = (dbTotal ?: 0) + hourSteps
                        android.util.Log.d(
                            "StepCounter",
                            "Daily total calculated: dbTotal=$dbTotal (excluding current hour $currentHourTimestamp=${java.util.Date(currentHourTimestamp)}), " +
                                    "currentHourSteps=$hourSteps, final=$finalTotal, startOfDay=${java.util.Date(effectiveStartOfDay)}"
                        )
                        finalTotal
                    }
            }.collect { total ->
                _dailySteps.value = total
            }
        }

        // Observe day history (database entries for today, excluding current hour)
        viewModelScope.launch {
            combine(
                preferences.lastStartOfDay,
                preferences.currentHourTimestamp
            ) { storedStartOfDay, currentHourTimestamp ->
                val effectiveStartOfDay = if (storedStartOfDay > 0) storedStartOfDay else getStartOfDay()
                Pair(effectiveStartOfDay, currentHourTimestamp)
            }.flatMapLatest { (effectiveStartOfDay, currentHourTimestamp) ->
                // Query now excludes the current hour
                repository.getStepsForDay(effectiveStartOfDay, currentHourTimestamp)
            }.collect { steps ->
                android.util.Log.d(
                    "StepCounter",
                    "History loaded (past hours): ${steps.size} entries - " +
                            steps.map { "${java.util.Date(it.timestamp)}: ${it.stepCount}" }.joinToString(", ")
                )
                _dayHistory.value = steps
            }
        }

        // Update current time periodically
        viewModelScope.launch {
            while (true) {
                _currentTime.value = System.currentTimeMillis()
                delay(1000) // Update every second
            }
        }

        // Periodic diagnostic logging (every 30 seconds)
        viewModelScope.launch {
            while (true) {
                delay(30000)  // Every 30 seconds
                val currentHourSteps = sensorManager.currentStepCount.value
                val hourlyStepsDisplayed = _hourlySteps.value
                val dailyStepsDisplayed = _dailySteps.value
                val currentDeviceTotal = sensorManager.getCurrentTotalSteps()

                android.util.Log.i(
                    "StepCounterDiagnostic",
                    "DIAGNOSTIC: " +
                            "currentDeviceTotal=$currentDeviceTotal | " +
                            "sensorCurrentStepCount=${sensorManager.currentStepCount.value} | " +
                            "displayedHourly=$hourlyStepsDisplayed | " +
                            "displayedDaily=$dailyStepsDisplayed | " +
                            "hour=${Calendar.getInstance().get(Calendar.HOUR_OF_DAY)}:${String.format("%02d", Calendar.getInstance().get(Calendar.MINUTE))}"
                )
            }
        }
    }

    suspend fun setLastHourStartStepCount(stepCount: Int) {
        sensorManager.setLastHourStartStepCount(stepCount)
    }

    /**
     * Refresh step counts when app resumes (e.g., from another fitness app like Samsung Health).
     * This ensures the sensor is responsive and displays are up-to-date.
     *
     * Defensive: Only refresh if sensor value increased or stayed same (never on decrease).
     * This prevents propagating stale values during mid-reset timing.
     *
     * IMPORTANT: Also handles closure period detection if UI hasn't been visible for a while.
     */
    fun refreshStepCounts() {
        // Guard: Don't execute if ViewModel hasn't been initialized
        // This prevents crashes when onResume() is called before initialize() completes
        if (!isInitialized || !::sensorManager.isInitialized || !::preferences.isInitialized) {
            android.util.Log.w("StepCounter", "refreshStepCounts: Skipping - ViewModel not fully initialized yet")
            return
        }

        viewModelScope.launch {
            startSensorSyncProbe("onResume")

            // Force recalculation based on current sensor state
            val currentTotal = sensorManager.getCurrentTotalSteps()
            val previousTotal = preferences.totalStepsDevice.first()

            if (currentTotal >= 0) {
                // Defensive check: Only refresh if sensor value didn't decrease
                // A decrease indicates we're in the middle of a sensor reset
                if (currentTotal < previousTotal) {
                    android.util.Log.w(
                        "StepCounter",
                        "⚠️ refreshStepCounts: SKIPPED - Sensor decreased from $previousTotal to $currentTotal. " +
                                "Likely mid-reset from another app accessing sensor. " +
                                "Waiting for next sensor event to update naturally."
                    )
                    return@launch
                }

                android.util.Log.d(
                    "StepCounter",
                    "refreshStepCounts: Current device steps = $currentTotal (was $previousTotal). " +
                            "Forcing sensor manager to recalculate hourly delta."
                )

                // Check for closure period - UI might have been invisible for hours/days
                // even if foreground service kept the app process alive
                handleUiResumeClosure(currentTotal)

                // Force the sensor manager to recalculate with current known values
                sensorManager.setLastKnownStepCount(currentTotal)

                android.util.Log.d("StepCounter", "refreshStepCounts: Display refreshed")
            } else {
                android.util.Log.w("StepCounter", "refreshStepCounts: Could not read sensor")
            }
        }
    }

    private fun startSensorSyncProbe(reason: String) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val lastEventTime = sensorManager.getLastSensorEventTime()

            if (isSensorReadingFresh(now, lastEventTime, freshEventWindowMs)) {
                _isSyncing.value = false
                syncProbeStartMs = 0L
                android.util.Log.d(
                    "StepCounter",
                    "Sensor sync probe skipped ($reason): last event is fresh (${now - lastEventTime}ms ago)"
                )
                return@launch
            }

            _isSyncing.value = true
            syncProbeStartMs = now

            sensorManager.flushSensor()
            val fresh = sensorManager.waitForSensorEventAfter(syncProbeStartMs, sensorSyncTimeoutMs)

            if (fresh) {
                _isSyncing.value = false
                syncProbeStartMs = 0L
                android.util.Log.i("StepCounter", "Sensor sync probe succeeded ($reason)")
            } else {
                android.util.Log.w(
                    "StepCounter",
                    "Sensor sync probe timed out ($reason). Keeping UI in syncing state until next callback."
                )
            }
        }
    }

    /**
     * Handles closure period detection when UI resumes after being invisible.
     * This is critical for apps with foreground services that keep the process alive -
     * initialize() won't be called again, so we need to detect closure here.
     */
    private suspend fun handleUiResumeClosure(currentDeviceTotal: Int) {
        val currentStartOfDay = getStartOfDay()
        val lastOpenDate = preferences.lastOpenDate.first()
        val currentHourTimestamp = Calendar.getInstance().apply {
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val savedHourTimestamp = preferences.currentHourTimestamp.first()
        val hoursDifference = if (savedHourTimestamp > 0) {
            (currentHourTimestamp - savedHourTimestamp) / (60 * 60 * 1000)
        } else {
            0
        }
        val shouldCheckMissedHours = savedHourTimestamp > 0 && hoursDifference >= 1

        val savedDeviceTotal = preferences.totalStepsDevice.first()
        val previousHourStartSteps = preferences.hourStartStepCount.first()
        val stepsWhileClosed = currentDeviceTotal - savedDeviceTotal
        val currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val isEarlyMorning = currentHour < StepTrackerConfig.MORNING_THRESHOLD_HOUR

        android.util.Log.i(
            "StepCounter",
            "handleUiResumeClosure: UI resume check. " +
                    "lastOpenDate=${if (lastOpenDate > 0) java.util.Date(lastOpenDate) else "never"}, " +
                    "today=${java.util.Date(currentStartOfDay)}, " +
                    "savedHour=${if (savedHourTimestamp > 0) java.util.Date(savedHourTimestamp) else "none"}, " +
                    "hoursDifference=$hoursDifference, stepsWhileClosed=$stepsWhileClosed, currentHour=$currentHour"
        )

        if (!shouldCheckMissedHours) {
            android.util.Log.d(
                "StepCounter",
                "handleUiResumeClosure: No missed-hour gap detected (hoursDifference=$hoursDifference). Skipping backfill."
            )
        } else {
            // App was closed across one or more hours (possibly across day boundary)
            android.util.Log.i(
                "StepCounter",
                "UI CLOSURE PERIOD: Evaluating missed hours from ${java.util.Date(savedHourTimestamp)} to ${java.util.Date(currentHourTimestamp)}"
            )

            // Validate savedDeviceTotal (reuse earlier bug guard)
            val validSavedDeviceTotal = if (savedDeviceTotal == 0 && previousHourStartSteps > 0) {
                android.util.Log.w(
                    "StepCounter",
                    "DETECTED BUG in UI resume logic: savedDeviceTotal=0 but hourStartStepCount=$previousHourStartSteps. " +
                            "Using hourStartStepCount as fallback."
                )
                previousHourStartSteps
            } else {
                savedDeviceTotal
            }

            val totalStepsWhileClosed = currentDeviceTotal - validSavedDeviceTotal
            if (totalStepsWhileClosed <= 0) {
                android.util.Log.w(
                    "StepCounter",
                    "UI resume: totalStepsWhileClosed=$totalStepsWhileClosed (no positive delta). Skipping backfill."
                )
            } else {
                val missingHours = mutableListOf<Long>()
                var accountedSteps = 0
                var hourCursor = savedHourTimestamp
                val hourCount = hoursDifference.toInt()

                for (i in 0 until hourCount) {
                    if (i < hourCount - 1) { // exclude current hour
                        val hourTs = hourCursor
                        if (hourTs >= currentStartOfDay && hourTs < currentHourTimestamp) {
                            val existing = repository.getStepForHour(hourTs)
                            if (existing != null) {
                                accountedSteps += existing.stepCount
                            } else {
                                missingHours.add(hourTs)
                            }
                        }
                        hourCursor += (60 * 60 * 1000)
                    }
                }

                val remainingSteps = totalStepsWhileClosed - accountedSteps
                if (missingHours.isEmpty()) {
                    android.util.Log.i(
                        "StepCounter",
                        "UI resume: No missing hours detected in range; accountedSteps=$accountedSteps, totalStepsWhileClosed=$totalStepsWhileClosed"
                    )
                } else if (remainingSteps <= 0) {
                    android.util.Log.w(
                        "StepCounter",
                        "UI resume: Remaining steps ($remainingSteps) <= 0 after accounting for existing hours. Skipping backfill."
                    )
                } else {
                    val stepsPerHour = remainingSteps / missingHours.size
                    android.util.Log.i(
                        "StepCounter",
                        "UI resume: Backfilling ${missingHours.size} missing hours with $remainingSteps steps (~$stepsPerHour/hour)"
                    )

                    for (hourTs in missingHours) {
                        val stepsClamped = minOf(stepsPerHour, StepTrackerConfig.MAX_STEPS_PER_HOUR)
                        android.util.Log.i(
                            "StepCounter",
                            "UI BACKFILL: Saving hour ${java.util.Date(hourTs)} ← $stepsClamped steps"
                        )
                        saveHourlyStepsIfEnabled(hourTs, stepsClamped, "UI backfill hour ${java.util.Date(hourTs)}")
                    }
                }
            }

            // UI is read-only for baselines: do NOT reset baseline here.
            // Just sync the timestamp if the sensor state looks valid.
            if (currentHourTimestamp != savedHourTimestamp) {
                val state = sensorManager.sensorState.value
                val sensorBaseline = state.lastHourStartStepCount
                val sensorCurrentSteps = state.currentHourSteps
                val sensorInitialized = state.isInitialized

                if (shouldSyncTimestamp(
                        sensorInitialized = sensorInitialized,
                        sensorBaseline = sensorBaseline,
                        sensorCurrentSteps = sensorCurrentSteps,
                        maxStepsPerHour = StepTrackerConfig.MAX_STEPS_PER_HOUR
                    )
                ) {
                    android.util.Log.i(
                        "StepCounter",
                        "Post-UI-closure: FG service has valid tracking (baseline=$sensorBaseline, " +
                                "currentHourSteps=$sensorCurrentSteps). UI preserving state."
                    )
                    preferences.saveCurrentHourTimestamp(currentHourTimestamp)
                } else {
                    android.util.Log.w(
                        "StepCounter",
                        "Post-UI-closure: Sensor not initialized or stale (initialized=$sensorInitialized, " +
                                "baseline=$sensorBaseline). UI will NOT reset baseline. " +
                                "Relying on FG service/worker to reconcile."
                    )
                }
            }

            // Record distribution time (UI-visible event only)
            preferences.saveLastDistributionTime(System.currentTimeMillis())
        }

        // Always update last open date when UI becomes visible
        preferences.saveLastOpenDate(currentStartOfDay)
        android.util.Log.d("StepCounter", "handleUiResumeClosure: Updated lastOpenDate to ${java.util.Date(currentStartOfDay)}")
    }

    override fun onCleared() {
        super.onCleared()
        // Don't stop the singleton sensor - ForegroundService may still be using it
        // System will clean up when app process is killed
    }

    private fun getStartOfDay(): Long {
        return Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private suspend fun saveHourlyStepsIfEnabled(timestamp: Long, steps: Int, reason: String) {
        if (!uiDbWritesEnabled) {
            android.util.Log.i(
                "StepCounter",
                "UI DB write disabled: $reason (timestamp=${java.util.Date(timestamp)}, steps=$steps)"
            )
            return
        }
        repository.saveHourlySteps(timestamp, steps)
    }
}
