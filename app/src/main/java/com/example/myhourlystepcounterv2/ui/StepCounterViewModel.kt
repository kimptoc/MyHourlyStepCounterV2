package com.example.myhourlystepcounterv2.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.util.Calendar

class StepCounterViewModel(private val repository: StepRepository) : ViewModel() {
    private lateinit var sensorManager: StepSensorManager
    private lateinit var preferences: StepPreferences

    private val _hourlySteps = MutableStateFlow(0)
    val hourlySteps: StateFlow<Int> = _hourlySteps.asStateFlow()

    private val _dailySteps = MutableStateFlow(0)
    val dailySteps: StateFlow<Int> = _dailySteps.asStateFlow()

    private val _dayHistory = MutableStateFlow<List<StepEntity>>(emptyList())
    val dayHistory: StateFlow<List<StepEntity>> = _dayHistory.asStateFlow()

    private val _currentTime = MutableStateFlow(System.currentTimeMillis())
    val currentTime: StateFlow<Long> = _currentTime.asStateFlow()

    fun initialize(context: Context) {
        sensorManager = StepSensorManager(context)
        preferences = StepPreferences(context)

        sensorManager.startListening()

        // Schedule the hourly work
        WorkManagerScheduler.scheduleHourlyStepCounter(context)

        // Initialize sensor manager with current sensor reading
        viewModelScope.launch {
            // Wait for sensor to provide actual reading
            delay(200) // Give sensor time to fire
            val actualDeviceSteps = sensorManager.getCurrentTotalSteps()

            if (actualDeviceSteps > 0) {
                // We have a real sensor reading
                val savedHourTimestamp = preferences.currentHourTimestamp.first()
                val currentHourTimestamp = Calendar.getInstance().apply {
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis

                // Check if we're in a new hour since last session
                if (currentHourTimestamp != savedHourTimestamp && savedHourTimestamp > 0) {
                    // Hour changed while app was closed - save previous hour data
                    val previousHourStartSteps = preferences.hourStartStepCount.first()
                    val savedDeviceTotal = preferences.totalStepsDevice.first()

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
                }

                // Initialize for current hour with actual device step count
                preferences.saveHourData(
                    hourStartStepCount = actualDeviceSteps,
                    currentTimestamp = currentHourTimestamp,
                    totalSteps = actualDeviceSteps
                )
                sensorManager.setLastHourStartStepCount(actualDeviceSteps)
                sensorManager.setLastKnownStepCount(actualDeviceSteps)
                android.util.Log.d("StepCounter", "App startup: Current device steps = $actualDeviceSteps")
            }
        }

        // Observe sensor step count
        viewModelScope.launch {
            sensorManager.currentStepCount.collect { steps ->
                _hourlySteps.value = steps
                // Save current device steps to preferences
                preferences.saveTotalStepsDevice(sensorManager.getCurrentTotalSteps())
            }
        }

        // Observe daily steps (database + current hour)
        val startOfDay = getStartOfDay()
        viewModelScope.launch {
            combine(
                repository.getTotalStepsForDay(startOfDay),
                sensorManager.currentStepCount
            ) { dbTotal, currentHourSteps ->
                // Add current hour's steps to the database total
                (dbTotal ?: 0) + currentHourSteps
            }.collect { total ->
                _dailySteps.value = total
            }
        }

        // Observe day history
        viewModelScope.launch {
            repository.getStepsForDay(startOfDay).collect { steps ->
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
    }

    fun scheduleHourBoundaryCheck() {
        viewModelScope.launch {
            while (true) {
                val now = Calendar.getInstance()
                val nextHour = Calendar.getInstance().apply {
                    add(Calendar.HOUR_OF_DAY, 1)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 1) // Check 1 second after hour starts
                    set(Calendar.MILLISECOND, 0)
                }
                val delayMs = nextHour.timeInMillis - now.timeInMillis

                android.util.Log.d("StepCounter", "Scheduled hour check in ${delayMs}ms (at ${nextHour.time})")
                delay(delayMs)

                checkAndResetHour()
            }
        }
    }

    fun checkAndResetHour() {
        val calendar = Calendar.getInstance()
        val currentHourTimestamp = calendar.apply {
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        viewModelScope.launch {
            try {
                val savedHourTimestamp = preferences.currentHourTimestamp.first()

                if (currentHourTimestamp != savedHourTimestamp) {
                    // Hour has changed! Save the previous hour's data
                    android.util.Log.d("StepCounter", "Hour boundary detected. Saved: $savedHourTimestamp, Current: $currentHourTimestamp")

                    val hourStartStepCount = preferences.hourStartStepCount.first()
                    // CRITICAL: Use stored device total from preferences, not sensor (which may be stale)
                    val currentDeviceSteps = preferences.totalStepsDevice.first()

                    // Calculate steps in the previous hour
                    var stepsInPreviousHour = currentDeviceSteps - hourStartStepCount

                    // Validate the delta - reject unreasonable values
                    val MAX_REASONABLE_STEPS_PER_HOUR = 10000
                    if (stepsInPreviousHour < 0) {
                        android.util.Log.w("StepCounter", "WARNING: Negative step delta ($stepsInPreviousHour). Sensor may have reset. Clamping to 0.")
                        stepsInPreviousHour = 0
                    } else if (stepsInPreviousHour > MAX_REASONABLE_STEPS_PER_HOUR) {
                        android.util.Log.w("StepCounter", "WARNING: Unreasonable step delta ($stepsInPreviousHour). Max expected is $MAX_REASONABLE_STEPS_PER_HOUR. Health app sync? Clamping to $MAX_REASONABLE_STEPS_PER_HOUR.")
                        stepsInPreviousHour = MAX_REASONABLE_STEPS_PER_HOUR
                    }

                    // Save the previous hour's data to database
                    repository.saveHourlySteps(savedHourTimestamp, stepsInPreviousHour)
                    android.util.Log.d("StepCounter", "Saved $stepsInPreviousHour steps for hour at ${Calendar.getInstance().apply { timeInMillis = savedHourTimestamp }.time}")

                    // Update preferences for the new hour
                    // New hour starts at current device step count
                    preferences.saveHourData(
                        hourStartStepCount = currentDeviceSteps,
                        currentTimestamp = currentHourTimestamp,
                        totalSteps = currentDeviceSteps
                    )

                    // Reset sensor manager for new hour
                    sensorManager.resetForNewHour(currentDeviceSteps)

                    // Force update of hourly steps to 0
                    _hourlySteps.value = 0
                } else {
                    android.util.Log.d("StepCounter", "Hour check at ${calendar.time} - still in same hour")
                }
            } catch (e: Exception) {
                android.util.Log.e("StepCounter", "Error in checkAndResetHour", e)
            }
        }
    }

    fun setLastHourStartStepCount(stepCount: Int) {
        sensorManager.setLastHourStartStepCount(stepCount)
    }

    override fun onCleared() {
        super.onCleared()
        sensorManager.stopListening()
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
