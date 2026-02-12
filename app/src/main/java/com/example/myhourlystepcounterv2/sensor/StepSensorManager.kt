package com.example.myhourlystepcounterv2.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.example.myhourlystepcounterv2.data.StepPreferences
import com.example.myhourlystepcounterv2.notifications.NotificationHelper
import com.example.myhourlystepcounterv2.StepTrackerConfig

internal suspend fun waitForFreshSensorEvent(
    sensorState: StateFlow<SensorState>,
    timestampMs: Long,
    timeoutMs: Long
): Boolean {
    return withTimeoutOrNull(timeoutMs) {
        sensorState.first { it.lastSensorEventTimeMs > timestampMs }
        true
    } ?: false
}

internal fun clampMonotonicHourSteps(previousDisplayed: Int, calculatedStepsThisHour: Int): Int {
    return maxOf(previousDisplayed, maxOf(0, calculatedStepsThisHour))
}

class StepSensorManager private constructor(context: Context) : SensorEventListener {
    private val sensorManager = context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    private val appContext = context.applicationContext
    private val preferences = StepPreferences(appContext)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Use Mutex for thread safety instead of ReentrantReadWriteLock
    private val stateMutex = Mutex()

    // Use StateFlow for reactive updates
    private val _sensorState = MutableStateFlow(SensorState())
    val sensorState: StateFlow<SensorState> = _sensorState.asStateFlow()

    // Expose currentStepCount as a separate StateFlow for backward compatibility
    private val _currentStepCount = MutableStateFlow(0)
    val currentStepCount: StateFlow<Int> = _currentStepCount.asStateFlow()

    companion object {
        @Volatile
        private var INSTANCE: StepSensorManager? = null

        fun getInstance(context: Context): StepSensorManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: StepSensorManager(context.applicationContext).also {
                    INSTANCE = it
                    android.util.Log.i("StepSensor", "Singleton instance created")
                }
            }
        }

        // For testing purposes only
        fun setInstance(instance: StepSensorManager) {
            INSTANCE = instance
        }

        fun resetInstance() {
            INSTANCE = null
        }
    }

    fun startListening() {
        stepSensor?.let {
            // Use maxReportLatencyUs to force the sensor HAL to deliver batched events
            // at least every 5 minutes, even during Doze mode. Without this parameter,
            // Samsung devices silently stop delivering TYPE_STEP_COUNTER events when
            // the screen is off.
            val maxReportLatencyUs = 5 * 60 * 1_000_000  // 5 minutes
            val registered = sensorManager.registerListener(
                this, it,
                SensorManager.SENSOR_DELAY_NORMAL,
                maxReportLatencyUs
            )
            android.util.Log.i(
                "StepSensor",
                "registerListener: SENSOR_DELAY_NORMAL, maxReportLatency=5min, registered=$registered"
            )
        }
    }

    fun stopListening() {
        sensorManager.unregisterListener(this)
    }

    /**
     * Forces immediate delivery of all pending batched sensor events from the hardware FIFO.
     * Call this before reading getCurrentTotalSteps() when data might be stale
     * (e.g., hour boundary processing after doze).
     */
    fun flushSensor(): Boolean {
        return stepSensor?.let {
            val result = sensorManager.flush(this)
            android.util.Log.d("StepSensor", "flushSensor(): result=$result")
            result
        } ?: false
    }

    fun getLastSensorEventTime(): Long {
        return _sensorState.value.lastSensorEventTimeMs
    }

    /**
     * Re-registers the sensor listener as a keepalive against Samsung's aggressive
     * power management that can silently stop delivering events.
     */
    fun reRegisterListener() {
        android.util.Log.i("StepSensor", "Re-registering sensor listener (keepalive)")
        stopListening()
        startListening()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_STEP_COUNTER) return

        // Skip sensor events during hour transitions to prevent race conditions
        if (_sensorState.value.hourTransitionInProgress) {
            android.util.Log.d("StepSensor", "Ignoring sensor event during transition: steps=${event.values[0]}")
            return
        }

        scope.launch {
            stateMutex.withLock {
                val stepCount = event.values[0].toInt()
                val currentState = _sensorState.value

                // Detect significant sensor resets (e.g., when another app like Samsung Health accesses sensor)
                // Only adjust if drop is significant (more than 10 steps), not just minor fluctuations
                val delta = stepCount - currentState.previousSensorValue
                var newState = currentState

                if (currentState.previousSensorValue > 0 && delta < -10) {  // Significant drop
                    val resetDelta = currentState.previousSensorValue - stepCount
                    val oldBaseline = currentState.lastHourStartStepCount

                    // Adjust the hour baseline DOWN by the same amount to preserve accumulated steps
                    // This maintains the delta: (newSensor - newBaseline) = (oldSensor - oldBaseline)
                    val newBaseline = maxOf(0, currentState.lastHourStartStepCount - resetDelta)

                    android.util.Log.w(
                        "StepSensor",
                        "🔄 SENSOR RESET DETECTED & ADJUSTED: previous=${currentState.previousSensorValue}, current=$stepCount, " +
                                "resetDelta=$resetDelta. Adjusted baseline: $oldBaseline → $newBaseline " +
                                "to preserve accumulated steps. Another app may have accessed the sensor (e.g., Samsung Health)."
                    )

                    // Update state with adjusted baseline
                    newState = currentState.copy(
                        lastHourStartStepCount = newBaseline,
                        previousSensorValue = stepCount,
                        lastKnownStepCount = stepCount
                    )

                    // Persist adjustment immediately - OUTSIDE mutex to avoid blocking
                    scope.launch {
                        preferences.saveHourStartStepCount(newBaseline)
                    }
                } else {
                    // Normal case - update state with new values
                    newState = currentState.copy(
                        previousSensorValue = stepCount,
                        lastKnownStepCount = stepCount
                    )
                }

                // Calculate steps for current hour
                val stepsThisHour = newState.lastKnownStepCount - newState.lastHourStartStepCount
                val finalSteps = clampMonotonicHourSteps(
                    previousDisplayed = currentState.currentHourSteps,
                    calculatedStepsThisHour = stepsThisHour
                )

                if (finalSteps > maxOf(0, stepsThisHour)) {
                    android.util.Log.w(
                        "StepSensor",
                        "Non-monotonic sensor reading ignored for display: previousDisplayed=${currentState.currentHourSteps}, " +
                                "calculatedNow=${maxOf(0, stepsThisHour)}, keeping=$finalSteps"
                    )
                }

                android.util.Log.d(
                    "StepSensor",
                    "Sensor fired: absolute=$stepCount | hourBaseline=${newState.lastHourStartStepCount} | delta=$stepsThisHour | initialized=${newState.isInitialized}"
                )

                // Record when this sensor event was received (for staleness detection)
                newState = newState.copy(lastSensorEventTimeMs = System.currentTimeMillis())

                // Only update display if initialized (prevents showing full device total on first fire)
                if (newState.isInitialized) {
                    newState = newState.copy(
                        currentHourSteps = finalSteps
                    )
                    _currentStepCount.value = finalSteps

                    // Check for achievements
                    checkForAchievement(newState, finalSteps)
                } else {
                    // Still not initialized, just update the internal state
                    newState = newState.copy(
                        currentHourSteps = finalSteps
                    )
                }

                // Update the state flow
                _sensorState.value = newState
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    suspend fun setLastHourStartStepCount(stepCount: Int) {
        stateMutex.withLock {
            val currentState = _sensorState.value
            val oldBaseline = currentState.lastHourStartStepCount

            val newState = currentState.copy(
                lastHourStartStepCount = stepCount,
                currentHourSteps = maxOf(0, currentState.lastKnownStepCount - stepCount)
            )

            android.util.Log.i(
                "StepSensor",
                "setLastHourStartStepCount: BASELINE_CHANGED from $oldBaseline to $stepCount | " +
                        "lastKnown=${newState.lastKnownStepCount} | calculated delta would be ${newState.lastKnownStepCount - stepCount}"
            )

            _sensorState.value = newState
            _currentStepCount.value = newState.currentHourSteps
        }
    }

    suspend fun setLastKnownStepCount(stepCount: Int) {
        stateMutex.withLock {
            val currentState = _sensorState.value

            android.util.Log.d("StepSensor", "setLastKnownStepCount: $stepCount (was ${currentState.lastKnownStepCount})")

            val newState = currentState.copy(
                lastKnownStepCount = stepCount,
                previousSensorValue = stepCount, // Update tracking for reset detection
                currentHourSteps = maxOf(0, stepCount - currentState.lastHourStartStepCount)
            )

            _sensorState.value = newState
            _currentStepCount.value = newState.currentHourSteps
        }
    }

    suspend fun markInitialized() {
        stateMutex.withLock {
            val currentState = _sensorState.value

            android.util.Log.d("StepSensor", "markInitialized: isInitialized=${currentState.isInitialized} -> true. About to call updateStepsForCurrentHour()")

            val newState = currentState.copy(
                isInitialized = true,
                currentHourSteps = maxOf(0, currentState.lastKnownStepCount - currentState.lastHourStartStepCount)
            )

            _sensorState.value = newState
            _currentStepCount.value = newState.currentHourSteps

            // Check for achievements after initialization
            if (newState.currentHourSteps >= StepTrackerConfig.STEP_REMINDER_THRESHOLD) {
                checkForAchievement(newState, newState.currentHourSteps)
            }
        }
    }

    private fun checkForAchievement(state: SensorState, currentSteps: Int) {
        // Track if we're below threshold (no coroutine needed)
        if (currentSteps < StepTrackerConfig.STEP_REMINDER_THRESHOLD) {
            // Update state to reflect we were below threshold
            scope.launch {
                stateMutex.withLock {
                    val currentState = _sensorState.value
                    if (!currentState.wasBelowThreshold) {
                        _sensorState.value = currentState.copy(wasBelowThreshold = true)
                    }
                }
            }
            return
        }

        // Only launch coroutine if we just crossed the threshold
        if (state.wasBelowThreshold) {
            scope.launch {
                try {
                    val reminderSent = preferences.reminderSentThisHour.first()
                    val achievementSent = preferences.achievementSentThisHour.first()

                    // Only send if reminder was sent this hour and achievement not yet sent
                    if (reminderSent && !achievementSent) {
                        android.util.Log.i(
                            "StepSensor",
                            "Achievement unlocked: $currentSteps steps >= ${StepTrackerConfig.STEP_REMINDER_THRESHOLD} after reminder"
                        )

                        NotificationHelper.sendStepAchievementNotification(appContext, currentSteps)

                        // Update the state first, then save to preferences (both outside mutex)
                        stateMutex.withLock {
                            val currentState = _sensorState.value
                            _sensorState.value = currentState.copy(wasBelowThreshold = false)
                        }

                        // Save to preferences outside mutex to avoid blocking
                        preferences.saveAchievementSentThisHour(true)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("StepSensor", "Error sending achievement notification", e)
                }
            }
        }
    }

    /**
     * Begins an hour transition, blocking sensor events from interfering
     */
    suspend fun beginHourTransition() {
        val startTime = System.currentTimeMillis()
        android.util.Log.i("StepSensor", "BEGIN hour transition at ${java.util.Date(startTime)}")
        stateMutex.withLock {
            val currentState = _sensorState.value
            _sensorState.value = currentState.copy(hourTransitionInProgress = true)
        }
        android.util.Log.d("StepSensor", "Hour transition started - sensor events will be deferred")
    }

    /**
     * Ends an hour transition, allowing sensor events to resume
     */
    suspend fun endHourTransition() {
        val endTime = System.currentTimeMillis()
        android.util.Log.i("StepSensor", "END hour transition at ${java.util.Date(endTime)}")
        stateMutex.withLock {
            val currentState = _sensorState.value
            _sensorState.value = currentState.copy(hourTransitionInProgress = false)
        }
        android.util.Log.d("StepSensor", "Hour transition completed - sensor events resumed")
    }

    /**
     * Resets the sensor baseline for a new hour. Use with begin/endHourTransition.
     * Returns false if this baseline was already set (duplicate call).
     */
    suspend fun resetForNewHour(currentStepCount: Int): Boolean {
        return stateMutex.withLock {
            val currentState = _sensorState.value

            // Prevent duplicate resets from race conditions
            if (currentState.lastHourStartStepCount == currentStepCount) {
                android.util.Log.d("StepSensor", "resetForNewHour: Baseline already set to $currentStepCount, ignoring duplicate")
                return@withLock false
            }

            val oldBaseline = currentState.lastHourStartStepCount
            val newState = currentState.copy(
                lastHourStartStepCount = currentStepCount,
                previousSensorValue = currentStepCount,  // Update tracking for reset detection
                currentHourSteps = 0,
                wasBelowThreshold = false  // Reset achievement tracking for new hour
            )

            _sensorState.value = newState
            _currentStepCount.value = 0

            android.util.Log.i("StepSensor", "resetForNewHour: Baseline set to $currentStepCount (was $oldBaseline), display reset to 0")
            return@withLock true
        }
    }

    /**
     * Update hour baseline only if it's different from current baseline.
     * Used by WorkManager which may run delayed - don't reset display if we're mid-hour.
     * Returns true if baseline was updated (meaning hour actually changed).
     */
    suspend fun updateHourBaselineIfNeeded(newBaseline: Int): Boolean {
        return stateMutex.withLock {
            val currentState = _sensorState.value

            if (currentState.lastHourStartStepCount != newBaseline) {
                android.util.Log.i(
                    "StepSensor",
                    "updateHourBaselineIfNeeded: Baseline changed from ${currentState.lastHourStartStepCount} to $newBaseline. " +
                    "Resetting display to 0 for new hour."
                )

                // Call resetForNewHour which is already synchronized
                return@withLock resetForNewHour(newBaseline)
            } else {
                android.util.Log.d(
                    "StepSensor",
                    "updateHourBaselineIfNeeded: Baseline already $newBaseline, skipping reset. " +
                    "Current hour steps: ${_currentStepCount.value} (preserved)"
                )
                return@withLock false
            }
        }
    }

    fun getCurrentTotalSteps(): Int {
        return _sensorState.value.lastKnownStepCount
    }

    /**
     * Waits for a new sensor callback after the provided timestamp.
     * Returns true if a fresh event arrives before timeout.
     */
    suspend fun waitForSensorEventAfter(timestampMs: Long, timeoutMs: Long): Boolean {
        return waitForFreshSensorEvent(sensorState, timestampMs, timeoutMs)
    }
}
