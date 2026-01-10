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
import kotlinx.coroutines.launch
import com.example.myhourlystepcounterv2.data.StepPreferences
import com.example.myhourlystepcounterv2.notifications.NotificationHelper
import com.example.myhourlystepcounterv2.StepTrackerConfig

class StepSensorManager private constructor(context: Context) : SensorEventListener {
    private val sensorManager = context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    private val appContext = context.applicationContext
    private val preferences = StepPreferences(appContext)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _currentStepCount = MutableStateFlow(0)
    val currentStepCount: StateFlow<Int> = _currentStepCount.asStateFlow()

    private var lastKnownStepCount = 0
    private var lastHourStartStepCount = 0
    private var isInitialized = false
    private var previousSensorValue = 0  // Track previous value to detect resets
    private var wasBelowThreshold = false  // Track if we were below threshold before

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
    }

    fun startListening() {
        stepSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stopListening() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_STEP_COUNTER) {
            val stepCount = event.values[0].toInt()

            // Detect significant sensor resets (e.g., when another app like Samsung Health accesses sensor)
            // Only ignore if drop is significant (more than 10 steps), not just minor fluctuations
            val delta = stepCount - previousSensorValue
            if (previousSensorValue > 0 && delta < -10) {  // Significant drop
                android.util.Log.w(
                    "StepSensor",
                    "SENSOR RESET DETECTED: previous=$previousSensorValue, current=$stepCount, delta=$delta. " +
                            "Another app may have accessed the sensor (e.g., Samsung Health). " +
                            "Keeping previous value to maintain data integrity."
                )
                // Don't update lastKnownStepCount - keep the previous value
                // This prevents the display from dropping to zero
                previousSensorValue = stepCount  // Track the reset for next detection
                return
            }

            val stepsThisHour = stepCount - lastHourStartStepCount
            android.util.Log.d(
                "StepSensor",
                "Sensor fired: absolute=$stepCount | hourBaseline=$lastHourStartStepCount | delta=$stepsThisHour | initialized=$isInitialized"
            )
            previousSensorValue = stepCount  // Track for reset detection
            lastKnownStepCount = stepCount
            // Only update display if initialized (prevents showing full device total on first fire)
            if (isInitialized) {
                updateStepsForCurrentHour()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    fun setLastHourStartStepCount(stepCount: Int) {
        val oldBaseline = lastHourStartStepCount
        lastHourStartStepCount = stepCount
        android.util.Log.i(
            "StepSensor",
            "setLastHourStartStepCount: BASELINE_CHANGED from $oldBaseline to $stepCount | " +
                    "lastKnown=$lastKnownStepCount | calculated delta would be ${lastKnownStepCount - stepCount}"
        )
        updateStepsForCurrentHour()
    }

    fun setLastKnownStepCount(stepCount: Int) {
        android.util.Log.d("StepSensor", "setLastKnownStepCount: $stepCount (was $lastKnownStepCount)")
        lastKnownStepCount = stepCount
        previousSensorValue = stepCount  // Update tracking for reset detection
        updateStepsForCurrentHour()
    }

    fun markInitialized() {
        android.util.Log.d("StepSensor", "markInitialized: isInitialized=$isInitialized -> true. About to call updateStepsForCurrentHour()")
        isInitialized = true
        // Recalculate with current values now that we're initialized
        updateStepsForCurrentHour()
    }

    private fun updateStepsForCurrentHour() {
        val stepsThisHour = lastKnownStepCount - lastHourStartStepCount
        val finalValue = maxOf(0, stepsThisHour)
        android.util.Log.d("StepSensor", "updateStepsForCurrentHour: lastKnown=$lastKnownStepCount - hourStart=$lastHourStartStepCount = $stepsThisHour -> finalValue=$finalValue, isInit=$isInitialized")

        // Store previous value for threshold detection
        val previousValue = _currentStepCount.value
        _currentStepCount.value = finalValue

        // Only check for achievement when crossing threshold (not on every step)
        if (isInitialized) {
            checkForAchievement(previousValue, finalValue)
        }
    }

    private fun checkForAchievement(previousSteps: Int, currentSteps: Int) {
        // Track if we're below threshold (no coroutine needed)
        if (currentSteps < StepTrackerConfig.STEP_REMINDER_THRESHOLD) {
            wasBelowThreshold = true
            return
        }

        // Only launch coroutine if we just crossed the threshold
        if (wasBelowThreshold &&
            previousSteps < StepTrackerConfig.STEP_REMINDER_THRESHOLD &&
            currentSteps >= StepTrackerConfig.STEP_REMINDER_THRESHOLD) {

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
                        preferences.saveAchievementSentThisHour(true)

                        // Reset the flag so we don't check again this hour
                        wasBelowThreshold = false
                    }
                } catch (e: Exception) {
                    android.util.Log.e("StepSensor", "Error sending achievement notification", e)
                }
            }
        }
    }

    fun resetForNewHour(currentStepCount: Int) {
        lastHourStartStepCount = currentStepCount
        previousSensorValue = currentStepCount  // Update tracking for reset detection
        _currentStepCount.value = 0
        wasBelowThreshold = false  // Reset achievement tracking for new hour
        android.util.Log.i("StepSensor", "resetForNewHour: Baseline set to $currentStepCount, display reset to 0")
    }

    /**
     * Update hour baseline only if it's different from current baseline.
     * Used by WorkManager which may run delayed - don't reset display if we're mid-hour.
     * Returns true if baseline was updated (meaning hour actually changed).
     */
    fun updateHourBaselineIfNeeded(newBaseline: Int): Boolean {
        if (lastHourStartStepCount != newBaseline) {
            android.util.Log.i(
                "StepSensor",
                "updateHourBaselineIfNeeded: Baseline changed from $lastHourStartStepCount to $newBaseline. " +
                "Resetting display to 0 for new hour."
            )
            resetForNewHour(newBaseline)
            return true
        } else {
            android.util.Log.d(
                "StepSensor",
                "updateHourBaselineIfNeeded: Baseline already $newBaseline, skipping reset. " +
                "Current hour steps: ${_currentStepCount.value} (preserved)"
            )
            return false
        }
    }

    fun getCurrentTotalSteps(): Int = lastKnownStepCount
}
