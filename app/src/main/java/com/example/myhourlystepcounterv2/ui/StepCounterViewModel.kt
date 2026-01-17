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
import com.example.myhourlystepcounterv2.sensor.StepSensorManager
import com.example.myhourlystepcounterv2.worker.WorkManagerScheduler
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

    // Guard to prevent duplicate initialization
    @Volatile
    private var isInitialized = false

    private val _hourlySteps = MutableStateFlow(0)
    val hourlySteps: StateFlow<Int> = _hourlySteps.asStateFlow()

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
        } else {
            android.util.Log.w("StepCounter", "Sensor listener NOT started - ACTIVITY_RECOGNITION permission denied")
        }

        // Schedule the hourly work
        WorkManagerScheduler.scheduleHourlyStepCounter(context)

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

            if (actualDeviceSteps >= 0) {  // Changed from > 0 to >= 0 to handle initial reading
                // We have a real sensor reading
                val savedHourTimestamp = preferences.currentHourTimestamp.first()
                val currentHourTimestamp = Calendar.getInstance().apply {
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis

                val previousHourStartSteps = preferences.hourStartStepCount.first()
                val savedDeviceTotal = preferences.totalStepsDevice.first()

                // Check for day boundary crossing and handle closure periods
                val currentStartOfDay = getStartOfDay()
                val lastOpenDate = preferences.lastOpenDate.first()
                val crossedDayBoundary = lastOpenDate > 0 && lastOpenDate < currentStartOfDay
                val isFirstOpenOfDay = lastOpenDate != currentStartOfDay

                if (crossedDayBoundary) {
                    // Day boundary crossed - handle yesterday's incomplete hour
                    android.util.Log.w(
                        "StepCounter",
                        "DAY BOUNDARY CROSSED at startup: lastOpenDate=${java.util.Date(lastOpenDate)}, " +
                                "today=${java.util.Date(currentStartOfDay)}"
                    )

                    // Save yesterday's incomplete hour as 0 (assume sleep period)
                    if (savedHourTimestamp > 0) {
                        repository.saveHourlySteps(savedHourTimestamp, 0)
                        android.util.Log.d("StepCounter", "Day boundary: Saved 0 steps for yesterday's incomplete hour")
                    }
                }

                if (isFirstOpenOfDay) {
                    // First open of this day - handle closure period with smart distribution
                    val stepsWhileClosed = actualDeviceSteps - savedDeviceTotal
                    val currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
                    val isEarlyMorning = currentHour < StepTrackerConfig.MORNING_THRESHOLD_HOUR

                    if (stepsWhileClosed > 10 && savedHourTimestamp > 0 && savedHourTimestamp < currentStartOfDay) {
                        // App was closed across a day boundary with significant steps
                        android.util.Log.i(
                            "StepCounter",
                            "FIRST OPEN OF DAY: stepsWhileClosed=$stepsWhileClosed, hour=$currentHour, " +
                                    "earlyMorning=$isEarlyMorning, threshold=${StepTrackerConfig.MORNING_THRESHOLD_HOUR}"
                        )

                        if (isEarlyMorning) {
                            // Early morning (before 10am): put all steps in current hour
                            val stepsClamped = minOf(stepsWhileClosed, StepTrackerConfig.MAX_STEPS_PER_HOUR)
                            android.util.Log.i("StepCounter", "Early morning: SAVING $stepsClamped steps to current hour ${java.util.Date(currentHourTimestamp)}")

                            // CRITICAL FIX: Save to database immediately
                            repository.saveHourlySteps(currentHourTimestamp, stepsClamped)

                            // Verify save
                            val saved = repository.getStepForHour(currentHourTimestamp)
                            android.util.Log.i("StepCounter", "Early morning: Verified DB has ${saved?.stepCount ?: 0} steps for current hour")
                        } else {
                            // Later in day: distribute steps evenly across waking hours (threshold onwards)
                            val hoursAwake = currentHour - StepTrackerConfig.MORNING_THRESHOLD_HOUR
                            if (hoursAwake > 0) {
                                val stepsPerHour = stepsWhileClosed / hoursAwake
                                android.util.Log.i(
                                    "StepCounter",
                                    "Later in day: distributing $stepsWhileClosed steps across $hoursAwake hours (~$stepsPerHour/hour)"
                                )

                                // Save distributed steps for hours from threshold to now
                                val thresholdCalendar = Calendar.getInstance().apply {
                                    set(Calendar.HOUR_OF_DAY, StepTrackerConfig.MORNING_THRESHOLD_HOUR)
                                    set(Calendar.MINUTE, 0)
                                    set(Calendar.SECOND, 0)
                                    set(Calendar.MILLISECOND, 0)
                                }

                                // Track which hours we're saving to prevent WorkManager conflicts
                                val distributedHours = mutableListOf<Long>()

                                for (hour in 0 until hoursAwake) {
                                    // Create fresh calendar for each hour to avoid accumulation
                                    val hourCalendar = Calendar.getInstance().apply {
                                        set(Calendar.HOUR_OF_DAY, StepTrackerConfig.MORNING_THRESHOLD_HOUR + hour)
                                        set(Calendar.MINUTE, 0)
                                        set(Calendar.SECOND, 0)
                                        set(Calendar.MILLISECOND, 0)
                                    }
                                    val hourTimestamp = hourCalendar.timeInMillis

                                    val stepsClamped = minOf(stepsPerHour, StepTrackerConfig.MAX_STEPS_PER_HOUR)
                                    android.util.Log.i("StepCounter", "CLOSURE DISTRIBUTION: Saving hour ${StepTrackerConfig.MORNING_THRESHOLD_HOUR + hour}:00 (${java.util.Date(hourTimestamp)}) ← $stepsClamped steps [STARTING]")

                                    // CRITICAL FIX: Save synchronously within this coroutine scope (already inside launch from line 80)
                                    repository.saveHourlySteps(hourTimestamp, stepsClamped)
                                    distributedHours.add(hourTimestamp)

                                    android.util.Log.i("StepCounter", "CLOSURE DISTRIBUTION: Hour ${StepTrackerConfig.MORNING_THRESHOLD_HOUR + hour}:00 ← $stepsClamped steps [COMMITTED]")
                                }

                                // CRITICAL FIX: Verify all saves committed
                                android.util.Log.i("StepCounter", "CLOSURE DISTRIBUTION COMPLETE: Verifying ${distributedHours.size} hours...")
                                for ((index, hourTs) in distributedHours.withIndex()) {
                                    val saved = repository.getStepForHour(hourTs)
                                    val hourNum = StepTrackerConfig.MORNING_THRESHOLD_HOUR + index
                                    android.util.Log.i("StepCounter", "  ✓ Hour $hourNum:00 → DB has ${saved?.stepCount ?: 0} steps")
                                }
                            }
                        }
                    }

                    // CRITICAL FIX: Update current hour baseline after distribution
                    // This ensures subsequent sensor readings calculate correctly
                    android.util.Log.i("StepCounter", "Post-distribution: Setting current hour baseline to $actualDeviceSteps")
                    preferences.saveHourData(
                        hourStartStepCount = actualDeviceSteps,
                        currentTimestamp = currentHourTimestamp,
                        totalSteps = actualDeviceSteps
                    )
                    sensorManager.setLastHourStartStepCount(actualDeviceSteps)
                    sensorManager.setLastKnownStepCount(actualDeviceSteps)
                    sensorManager.markInitialized()

                    // Record distribution time for WorkManager coordination
                    preferences.saveLastDistributionTime(System.currentTimeMillis())
                    android.util.Log.i("StepCounter", "Closure distribution timestamp recorded")

                    // Update last open date
                    preferences.saveLastOpenDate(currentStartOfDay)
                }

                // Check if we're in a new hour since last session
                if (currentHourTimestamp != savedHourTimestamp && savedHourTimestamp > 0) {
                    // Hour changed while app was closed - save previous hour data
                    if (previousHourStartSteps > 0 && savedDeviceTotal > 0) {
                        var stepsInPreviousHour = actualDeviceSteps - previousHourStartSteps

                        // Validate
                        if (stepsInPreviousHour < 0) {
                            stepsInPreviousHour = 0
                        } else if (stepsInPreviousHour > 10000) {
                            stepsInPreviousHour = 10000
                        }

                        repository.saveHourlySteps(savedHourTimestamp, stepsInPreviousHour)
                        android.util.Log.d("StepCounter", "App startup: Saved $stepsInPreviousHour steps for previous hour")
                    }

                    // Initialize for current hour with actual device step count
                    android.util.Log.d("StepCounter", "App startup: Initializing with actual device steps = $actualDeviceSteps, hour = ${Calendar.getInstance().get(Calendar.HOUR_OF_DAY)}")
                    preferences.saveHourData(
                        hourStartStepCount = actualDeviceSteps,
                        currentTimestamp = currentHourTimestamp,
                        totalSteps = actualDeviceSteps
                    )
                    sensorManager.setLastHourStartStepCount(actualDeviceSteps)
                    sensorManager.setLastKnownStepCount(actualDeviceSteps)
                    sensorManager.markInitialized()
                    android.util.Log.d("StepCounter", "App startup: Set sensor manager - hour start = $actualDeviceSteps, known total = $actualDeviceSteps")
                } else {
                    // Same hour as last session
                    val stepsWhileAppWasClosed = actualDeviceSteps - savedDeviceTotal

                    if (stepsWhileAppWasClosed > 10 && previousHourStartSteps > 0) {
                        // App was closed in the same hour and steps were taken
                        // Restore previous baseline to preserve what steps we CAN track
                        val hourTimestamp = java.util.Date(savedHourTimestamp)
                        val now = java.util.Date()
                        android.util.Log.w(
                            "StepCounter",
                            "APP CLOSED MID-HOUR: " +
                                    "hourStartTime=$hourTimestamp | " +
                                    "now=$now | " +
                                    "stepsWhileClosed=$stepsWhileAppWasClosed | " +
                                    "baseline=$previousHourStartSteps | " +
                                    "current=$actualDeviceSteps | " +
                                    "willShow=${actualDeviceSteps - previousHourStartSteps}"
                        )
                        sensorManager.setLastHourStartStepCount(previousHourStartSteps)
                        sensorManager.setLastKnownStepCount(actualDeviceSteps)
                        sensorManager.markInitialized()
                    } else {
                        // Normal case: app was just backgrounded/resumed in same hour
                        val hourTimestamp = java.util.Date(savedHourTimestamp)
                        val now = java.util.Date()
                        android.util.Log.d(
                            "StepCounter",
                            "App startup: same-hour resume | " +
                                    "hourStartTime=$hourTimestamp | " +
                                    "now=$now | " +
                                    "baseline=$previousHourStartSteps | " +
                                    "current=$actualDeviceSteps | " +
                                    "willShow=${actualDeviceSteps - previousHourStartSteps}"
                        )
                        sensorManager.setLastHourStartStepCount(previousHourStartSteps)
                        sensorManager.setLastKnownStepCount(actualDeviceSteps)
                        sensorManager.markInitialized()
                    }
                }
            }
        }

        // Observe sensor step count
        viewModelScope.launch {
            sensorManager.currentStepCount.collect { steps ->
                _hourlySteps.value = steps

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

        // Setup day boundary detection and daily step observation
        viewModelScope.launch {
            // Check for day boundary at startup
            val currentStartOfDay = getStartOfDay()
            val lastStoredStartOfDay = preferences.lastStartOfDay.first()

            if (lastStoredStartOfDay > 0 && lastStoredStartOfDay != currentStartOfDay) {
                android.util.Log.w(
                    "StepCounter",
                    "DAY BOUNDARY DETECTED: Previous=${java.util.Date(lastStoredStartOfDay)} | Today=${java.util.Date(currentStartOfDay)}"
                )

                // Reset for new day - set the hour baseline to current device steps
                val currentDeviceSteps = sensorManager.getCurrentTotalSteps()
                val currentHourTimestamp = Calendar.getInstance().apply {
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis

                android.util.Log.d("StepCounter", "Day reset: Setting new hour baseline to $currentDeviceSteps")
                preferences.saveHourData(
                    hourStartStepCount = currentDeviceSteps,
                    currentTimestamp = currentHourTimestamp,
                    totalSteps = currentDeviceSteps
                )
                sensorManager.setLastHourStartStepCount(currentDeviceSteps)
                sensorManager.setLastKnownStepCount(currentDeviceSteps)
            }

            // Save the current start of day
            preferences.saveStartOfDay(currentStartOfDay)
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

        // Observe day history (database entries for today)
        viewModelScope.launch {
            preferences.lastStartOfDay.flatMapLatest { storedStartOfDay ->
                val effectiveStartOfDay = if (storedStartOfDay > 0) storedStartOfDay else getStartOfDay()
                repository.getStepsForDay(effectiveStartOfDay)
            }.collect { steps ->
                android.util.Log.d("StepCounter", "History loaded: ${steps.size} entries - ${steps.map { "${it.timestamp}: ${it.stepCount}" }.joinToString(", ")}")
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

    fun setLastHourStartStepCount(stepCount: Int) {
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

        // Check if this is first UI open of the day
        val isFirstOpenOfDay = lastOpenDate != currentStartOfDay

        if (!isFirstOpenOfDay) {
            android.util.Log.d("StepCounter", "handleUiResumeClosure: Not first open today, skipping closure detection")
            return
        }

        val savedDeviceTotal = preferences.totalStepsDevice.first()
        val savedHourTimestamp = preferences.currentHourTimestamp.first()
        val stepsWhileClosed = currentDeviceTotal - savedDeviceTotal
        val currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val isEarlyMorning = currentHour < StepTrackerConfig.MORNING_THRESHOLD_HOUR

        android.util.Log.i(
            "StepCounter",
            "handleUiResumeClosure: FIRST UI OPEN TODAY detected! " +
                    "lastOpenDate=${if (lastOpenDate > 0) java.util.Date(lastOpenDate) else "never"}, " +
                    "today=${java.util.Date(currentStartOfDay)}, " +
                    "stepsWhileClosed=$stepsWhileClosed, currentHour=$currentHour"
        )

        if (stepsWhileClosed > 10 && savedHourTimestamp > 0 && savedHourTimestamp < currentStartOfDay) {
            // App was closed across a day boundary with significant steps
            android.util.Log.i(
                "StepCounter",
                "UI CLOSURE PERIOD: Distributing $stepsWhileClosed steps. earlyMorning=$isEarlyMorning"
            )

            if (isEarlyMorning) {
                // Early morning (before 10am): put all steps in current hour
                val stepsClamped = minOf(stepsWhileClosed, StepTrackerConfig.MAX_STEPS_PER_HOUR)
                android.util.Log.i("StepCounter", "Early morning UI resume: SAVING $stepsClamped steps to current hour ${java.util.Date(currentHourTimestamp)}")

                repository.saveHourlySteps(currentHourTimestamp, stepsClamped)

                // Verify save
                val saved = repository.getStepForHour(currentHourTimestamp)
                android.util.Log.i("StepCounter", "Early morning UI resume: Verified DB has ${saved?.stepCount ?: 0} steps for current hour")
            } else {
                // Later in day: distribute steps evenly across waking hours (threshold onwards)
                val hoursAwake = currentHour - StepTrackerConfig.MORNING_THRESHOLD_HOUR
                if (hoursAwake > 0) {
                    val stepsPerHour = stepsWhileClosed / hoursAwake
                    android.util.Log.i(
                        "StepCounter",
                        "Later in day UI resume: distributing $stepsWhileClosed steps across $hoursAwake hours (~$stepsPerHour/hour)"
                    )

                    // Save distributed steps for hours from threshold to now
                    val distributedHours = mutableListOf<Long>()

                    for (hour in 0 until hoursAwake) {
                        // Create fresh calendar for each hour to avoid accumulation
                        val hourCalendar = Calendar.getInstance().apply {
                            set(Calendar.HOUR_OF_DAY, StepTrackerConfig.MORNING_THRESHOLD_HOUR + hour)
                            set(Calendar.MINUTE, 0)
                            set(Calendar.SECOND, 0)
                            set(Calendar.MILLISECOND, 0)
                        }
                        val hourTimestamp = hourCalendar.timeInMillis

                        val stepsClamped = minOf(stepsPerHour, StepTrackerConfig.MAX_STEPS_PER_HOUR)
                        android.util.Log.i("StepCounter", "UI CLOSURE DISTRIBUTION: Saving hour ${StepTrackerConfig.MORNING_THRESHOLD_HOUR + hour}:00 (${java.util.Date(hourTimestamp)}) ← $stepsClamped steps")

                        repository.saveHourlySteps(hourTimestamp, stepsClamped)
                        distributedHours.add(hourTimestamp)
                    }

                    // Verify all saves committed
                    android.util.Log.i("StepCounter", "UI CLOSURE DISTRIBUTION COMPLETE: Verifying ${distributedHours.size} hours...")
                    for ((index, hourTs) in distributedHours.withIndex()) {
                        val saved = repository.getStepForHour(hourTs)
                        val hourNum = StepTrackerConfig.MORNING_THRESHOLD_HOUR + index
                        android.util.Log.i("StepCounter", "  ✓ Hour $hourNum:00 → DB has ${saved?.stepCount ?: 0} steps")
                    }
                }
            }

            // Update current hour baseline after distribution
            android.util.Log.i("StepCounter", "Post-UI-closure-distribution: Setting current hour baseline to $currentDeviceTotal")
            preferences.saveHourData(
                hourStartStepCount = currentDeviceTotal,
                currentTimestamp = currentHourTimestamp,
                totalSteps = currentDeviceTotal
            )
            sensorManager.setLastHourStartStepCount(currentDeviceTotal)
            sensorManager.markInitialized()

            // Record distribution time
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
}
