package com.example.myhourlystepcounterv2.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class StepSensorManager(context: Context) : SensorEventListener {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

    private val _currentStepCount = MutableStateFlow(0)
    val currentStepCount: StateFlow<Int> = _currentStepCount.asStateFlow()

    private var lastKnownStepCount = 0
    private var lastHourStartStepCount = 0
    private var isInitialized = false
    private var previousSensorValue = 0  // Track previous value to detect resets

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

            // Detect sensor resets (e.g., when another app like Samsung Health accesses sensor)
            if (previousSensorValue > 0 && stepCount < previousSensorValue) {
                android.util.Log.w(
                    "StepSensor",
                    "SENSOR RESET DETECTED: previous=$previousSensorValue, current=$stepCount. " +
                            "Another app may have accessed the sensor (e.g., Samsung Health). " +
                            "Keeping previous value to maintain data integrity."
                )
                // Don't update lastKnownStepCount - keep the previous value
                // This prevents the display from dropping to zero
                previousSensorValue = stepCount  // Track the reset for next detection
                return
            }

            android.util.Log.d("StepSensor", "Sensor fired: absolute steps = $stepCount, last hour start = $lastHourStartStepCount, isInitialized = $isInitialized, current delta = ${stepCount - lastHourStartStepCount}")
            previousSensorValue = stepCount  // Track for reset detection
            lastKnownStepCount = stepCount
            // Only update display if initialized (prevents showing full device total on first fire)
            if (isInitialized) {
                updateStepsForCurrentHour()
            }
            android.util.Log.d("StepSensor", "Updated: _currentStepCount = ${_currentStepCount.value}, lastKnownStepCount = $lastKnownStepCount")
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    fun setLastHourStartStepCount(stepCount: Int) {
        lastHourStartStepCount = stepCount
        updateStepsForCurrentHour()
    }

    fun setLastKnownStepCount(stepCount: Int) {
        lastKnownStepCount = stepCount
        previousSensorValue = stepCount  // Update tracking for reset detection
        updateStepsForCurrentHour()
    }

    fun markInitialized() {
        isInitialized = true
        // Recalculate with current values now that we're initialized
        updateStepsForCurrentHour()
    }

    private fun updateStepsForCurrentHour() {
        val stepsThisHour = lastKnownStepCount - lastHourStartStepCount
        _currentStepCount.value = maxOf(0, stepsThisHour)
    }

    fun resetForNewHour(currentStepCount: Int) {
        lastHourStartStepCount = currentStepCount
        previousSensorValue = currentStepCount  // Update tracking for reset detection
        _currentStepCount.value = 0
    }

    fun getCurrentTotalSteps(): Int = lastKnownStepCount
}
