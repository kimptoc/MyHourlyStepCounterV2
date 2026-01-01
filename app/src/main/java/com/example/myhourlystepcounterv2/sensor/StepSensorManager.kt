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
            lastKnownStepCount = stepCount
            updateStepsForCurrentHour()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    fun setLastHourStartStepCount(stepCount: Int) {
        lastHourStartStepCount = stepCount
        updateStepsForCurrentHour()
    }

    fun setLastKnownStepCount(stepCount: Int) {
        lastKnownStepCount = stepCount
        updateStepsForCurrentHour()
    }

    private fun updateStepsForCurrentHour() {
        val stepsThisHour = lastKnownStepCount - lastHourStartStepCount
        _currentStepCount.value = maxOf(0, stepsThisHour)
    }

    fun resetForNewHour(currentStepCount: Int) {
        lastHourStartStepCount = currentStepCount
        _currentStepCount.value = 0
    }

    fun getCurrentTotalSteps(): Int = lastKnownStepCount
}
