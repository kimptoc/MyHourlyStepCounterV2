package com.example.myhourlystepcounterv2.data

import android.content.Context
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
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
        val LAST_DISTRIBUTION_TIME = longPreferencesKey("last_distribution_time")

        // New preferences: permanent notification and wake lock
        val PERMANENT_NOTIFICATION_ENABLED = booleanPreferencesKey("permanent_notification_enabled")
        val USE_WAKE_LOCK = booleanPreferencesKey("use_wake_lock")

        // Reminder notification preferences
        val REMINDER_NOTIFICATION_ENABLED = booleanPreferencesKey("reminder_notification_enabled")
        val LAST_REMINDER_NOTIFICATION_TIME = longPreferencesKey("last_reminder_notification_time")

        // Defaults (user requested defaults ON)
        const val PERMANENT_NOTIFICATION_DEFAULT = true
        const val USE_WAKE_LOCK_DEFAULT = true
        const val REMINDER_NOTIFICATION_DEFAULT = true
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

    val lastDistributionTime: Flow<Long> = context.dataStore.data
        .map { preferences -> preferences[LAST_DISTRIBUTION_TIME] ?: 0 }

    // New flows for permanent notification and wake-lock
    val permanentNotificationEnabled: Flow<Boolean> = context.dataStore.data
        .map { preferences -> preferences[PERMANENT_NOTIFICATION_ENABLED] ?: PERMANENT_NOTIFICATION_DEFAULT }

    val useWakeLock: Flow<Boolean> = context.dataStore.data
        .map { preferences -> preferences[USE_WAKE_LOCK] ?: USE_WAKE_LOCK_DEFAULT }

    val reminderNotificationEnabled: Flow<Boolean> = context.dataStore.data
        .map { preferences -> preferences[REMINDER_NOTIFICATION_ENABLED] ?: REMINDER_NOTIFICATION_DEFAULT }

    val lastReminderNotificationTime: Flow<Long> = context.dataStore.data
        .map { preferences -> preferences[LAST_REMINDER_NOTIFICATION_TIME] ?: 0L }

    suspend fun savePermanentNotificationEnabled(enabled: Boolean) {
        context.dataStore.updateData { preferences ->
            preferences.toMutablePreferences().apply {
                this[PERMANENT_NOTIFICATION_ENABLED] = enabled
            }
        }
    }

    suspend fun saveUseWakeLock(enabled: Boolean) {
        context.dataStore.updateData { preferences ->
            preferences.toMutablePreferences().apply {
                this[USE_WAKE_LOCK] = enabled
            }
        }
    }

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

    suspend fun saveLastDistributionTime(timestamp: Long) {
        context.dataStore.updateData { preferences ->
            preferences.toMutablePreferences().apply {
                this[LAST_DISTRIBUTION_TIME] = timestamp
            }
        }
    }

    suspend fun saveReminderNotificationEnabled(enabled: Boolean) {
        context.dataStore.updateData { preferences ->
            preferences.toMutablePreferences().apply {
                this[REMINDER_NOTIFICATION_ENABLED] = enabled
            }
        }
    }

    suspend fun saveLastReminderNotificationTime(timestamp: Long) {
        context.dataStore.updateData { preferences ->
            preferences.toMutablePreferences().apply {
                this[LAST_REMINDER_NOTIFICATION_TIME] = timestamp
            }
        }
    }
}
