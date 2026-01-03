package com.example.myhourlystepcounterv2.data

import android.content.Context
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("step_preferences")

class StepPreferences(private val context: Context) {
    companion object {
        val HOUR_START_STEP_COUNT = intPreferencesKey("hour_start_step_count")
        val CURRENT_HOUR_TIMESTAMP = longPreferencesKey("current_hour_timestamp")
        val TOTAL_STEPS_DEVICE = intPreferencesKey("total_steps_device")
        val LAST_START_OF_DAY = longPreferencesKey("last_start_of_day")
        val LAST_OPEN_DATE = longPreferencesKey("last_open_date")
    }

    val hourStartStepCount: Flow<Int> = context.dataStore.data
        .map { preferences -> preferences[HOUR_START_STEP_COUNT] ?: 0 }

    val currentHourTimestamp: Flow<Long> = context.dataStore.data
        .map { preferences -> preferences[CURRENT_HOUR_TIMESTAMP] ?: System.currentTimeMillis() }

    val totalStepsDevice: Flow<Int> = context.dataStore.data
        .map { preferences -> preferences[TOTAL_STEPS_DEVICE] ?: 0 }

    val lastStartOfDay: Flow<Long> = context.dataStore.data
        .map { preferences -> preferences[LAST_START_OF_DAY] ?: 0 }

    val lastOpenDate: Flow<Long> = context.dataStore.data
        .map { preferences -> preferences[LAST_OPEN_DATE] ?: 0 }

    suspend fun saveHourStartStepCount(stepCount: Int) {
        context.dataStore.updateData { preferences ->
            preferences.toMutablePreferences().apply {
                this[HOUR_START_STEP_COUNT] = stepCount
            }
        }
    }

    suspend fun saveCurrentHourTimestamp(timestamp: Long) {
        context.dataStore.updateData { preferences ->
            preferences.toMutablePreferences().apply {
                this[CURRENT_HOUR_TIMESTAMP] = timestamp
            }
        }
    }

    suspend fun saveTotalStepsDevice(stepCount: Int) {
        context.dataStore.updateData { preferences ->
            preferences.toMutablePreferences().apply {
                this[TOTAL_STEPS_DEVICE] = stepCount
            }
        }
    }

    suspend fun saveHourData(hourStartStepCount: Int, currentTimestamp: Long, totalSteps: Int) {
        context.dataStore.updateData { preferences ->
            preferences.toMutablePreferences().apply {
                this[HOUR_START_STEP_COUNT] = hourStartStepCount
                this[CURRENT_HOUR_TIMESTAMP] = currentTimestamp
                this[TOTAL_STEPS_DEVICE] = totalSteps
            }
        }
    }

    suspend fun saveStartOfDay(startOfDay: Long) {
        context.dataStore.updateData { preferences ->
            preferences.toMutablePreferences().apply {
                this[LAST_START_OF_DAY] = startOfDay
            }
        }
    }

    suspend fun saveLastOpenDate(date: Long) {
        context.dataStore.updateData { preferences ->
            preferences.toMutablePreferences().apply {
                this[LAST_OPEN_DATE] = date
            }
        }
    }
}
