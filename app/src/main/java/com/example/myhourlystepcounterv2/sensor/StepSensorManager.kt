package com.example.myhourlystepcounterv2.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class StepSensorManager private constructor(context: Context) : SensorEventListener {
    private val sensorManager = context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

    private val _currentStepCount = MutableStateFlow(0)
    val currentStepCount: StateFlow<Int> = _currentStepCount.asStateFlow()

    private var lastKnownStepCount = 0
    private var lastHourStartStepCount = 0
    private var isInitialized = false
    private var previousSensorValue = 0  // Track previous value to detect resets

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
        _currentStepCount.value = finalValue
    }

    fun resetForNewHour(currentStepCount: Int) {
        lastHourStartStepCount = currentStepCount
        previousSensorValue = currentStepCount  // Update tracking for reset detection
        _currentStepCount.value = 0
    }

    fun getCurrentTotalSteps(): Int = lastKnownStepCount
}
