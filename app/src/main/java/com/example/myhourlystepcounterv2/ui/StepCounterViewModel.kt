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

        // Initialize sensor manager with saved hour start step count
        viewModelScope.launch {
            val savedHourStartStepCount = preferences.hourStartStepCount.first()
            val currentDeviceSteps = preferences.totalStepsDevice.first()

            if (currentDeviceSteps > 0) {
                // We have previous data, restore it
                sensorManager.setLastHourStartStepCount(savedHourStartStepCount)
                sensorManager.setLastKnownStepCount(currentDeviceSteps)
            } else {
                // First run, get current steps and initialize
                delay(100) // Wait for sensor to be ready
                val currentSteps = sensorManager.getCurrentTotalSteps()
                if (currentSteps > 0) {
                    val hourStart = Calendar.getInstance().apply {
                        set(Calendar.MINUTE, 0)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }.timeInMillis
                    preferences.saveHourData(currentSteps, hourStart, currentSteps)
                    sensorManager.setLastHourStartStepCount(currentSteps)
                    sensorManager.setLastKnownStepCount(currentSteps)
                }
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
                    val currentDeviceSteps = sensorManager.getCurrentTotalSteps()

                    // Calculate steps in the previous hour
                    val stepsInPreviousHour = currentDeviceSteps - hourStartStepCount

                    // Save the previous hour's data to database
                    repository.saveHourlySteps(savedHourTimestamp, maxOf(0, stepsInPreviousHour))
                    android.util.Log.d("StepCounter", "Saved ${maxOf(0, stepsInPreviousHour)} steps for hour at ${Calendar.getInstance().apply { timeInMillis = savedHourTimestamp }.time}")

                    // Update preferences for the new hour
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
