package com.example.myhourlystepcounterv2.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myhourlystepcounterv2.data.StepDatabase
import com.example.myhourlystepcounterv2.data.StepEntity
import com.example.myhourlystepcounterv2.data.StepRepository
import com.example.myhourlystepcounterv2.sensor.StepSensorManager
import com.example.myhourlystepcounterv2.worker.WorkManagerScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.util.Calendar

class StepCounterViewModel(private val repository: StepRepository) : ViewModel() {
    private lateinit var sensorManager: StepSensorManager

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
        sensorManager.startListening()

        // Schedule the hourly work
        WorkManagerScheduler.scheduleHourlyStepCounter(context)

        // Observe sensor step count
        viewModelScope.launch {
            sensorManager.currentStepCount.collect { steps ->
                _hourlySteps.value = steps
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
                kotlinx.coroutines.delay(1000) // Update every second
            }
        }
    }

    fun checkAndResetHour() {
        val calendar = Calendar.getInstance()
        val currentHour = calendar.get(Calendar.HOUR_OF_DAY)
        val previousHour = if (currentHour == 0) 23 else currentHour - 1

        // Check if we've crossed into a new hour
        val hourStartTimestamp = calendar.apply {
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        viewModelScope.launch {
            val existingRecord = repository.getStepForHour(hourStartTimestamp)
            if (existingRecord == null) {
                // New hour detected, reset sensor for new hour tracking
                sensorManager.resetForNewHour(sensorManager.getCurrentTotalSteps())
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
