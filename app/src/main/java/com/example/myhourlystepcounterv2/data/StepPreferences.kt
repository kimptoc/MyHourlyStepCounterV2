package com.example.myhourlystepcounterv2.data

import android.content.Context
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

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
        val REMINDER_SENT_THIS_HOUR = booleanPreferencesKey("reminder_sent_this_hour")
        val ACHIEVEMENT_SENT_THIS_HOUR = booleanPreferencesKey("achievement_sent_this_hour")

        // Deduplication preference
        val LAST_PROCESSED_BOUNDARY_TIMESTAMP = longPreferencesKey("last_processed_boundary_timestamp")
        val LAST_PROCESSED_RANGE_START = longPreferencesKey("last_processed_range_start")
        val LAST_PROCESSED_RANGE_END = longPreferencesKey("last_processed_range_end")

        // Snapshot preferences (rolling 24h window)
        val DEVICE_TOTAL_SNAPSHOTS = stringPreferencesKey("device_total_snapshots_json")

        // Second reminder (XX:55) preferences
        val LAST_SECOND_REMINDER_TIME = longPreferencesKey("last_second_reminder_time")
        val SECOND_REMINDER_SENT_THIS_HOUR = booleanPreferencesKey("second_reminder_sent_this_hour")

        // Defaults (user requested defaults ON)
        const val PERMANENT_NOTIFICATION_DEFAULT = true
        const val USE_WAKE_LOCK_DEFAULT = true
        const val REMINDER_NOTIFICATION_DEFAULT = true

        private const val SNAPSHOT_RETENTION_MS = 24 * 60 * 60 * 1000L
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

    val reminderSentThisHour: Flow<Boolean> = context.dataStore.data
        .map { preferences -> preferences[REMINDER_SENT_THIS_HOUR] ?: false }

    val achievementSentThisHour: Flow<Boolean> = context.dataStore.data
        .map { preferences -> preferences[ACHIEVEMENT_SENT_THIS_HOUR] ?: false }

    val lastSecondReminderTime: Flow<Long> = context.dataStore.data
        .map { preferences -> preferences[LAST_SECOND_REMINDER_TIME] ?: 0L }

    val secondReminderSentThisHour: Flow<Boolean> = context.dataStore.data
        .map { preferences -> preferences[SECOND_REMINDER_SENT_THIS_HOUR] ?: false }

    val lastProcessedBoundaryTimestamp: Flow<Long> = context.dataStore.data
        .map { preferences -> preferences[LAST_PROCESSED_BOUNDARY_TIMESTAMP] ?: 0L }

    val lastProcessedRangeStart: Flow<Long> = context.dataStore.data
        .map { preferences -> preferences[LAST_PROCESSED_RANGE_START] ?: 0L }

    val lastProcessedRangeEnd: Flow<Long> = context.dataStore.data
        .map { preferences -> preferences[LAST_PROCESSED_RANGE_END] ?: 0L }

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

    suspend fun saveReminderSentThisHour(sent: Boolean) {
        context.dataStore.updateData { preferences ->
            preferences.toMutablePreferences().apply {
                this[REMINDER_SENT_THIS_HOUR] = sent
            }
        }
    }

    suspend fun saveAchievementSentThisHour(sent: Boolean) {
        context.dataStore.updateData { preferences ->
            preferences.toMutablePreferences().apply {
                this[ACHIEVEMENT_SENT_THIS_HOUR] = sent
            }
        }
    }

    suspend fun saveLastSecondReminderTime(timestamp: Long) {
        context.dataStore.updateData { preferences ->
            preferences.toMutablePreferences().apply {
                this[LAST_SECOND_REMINDER_TIME] = timestamp
            }
        }
    }

    suspend fun saveSecondReminderSentThisHour(sent: Boolean) {
        context.dataStore.updateData { preferences ->
            preferences.toMutablePreferences().apply {
                this[SECOND_REMINDER_SENT_THIS_HOUR] = sent
            }
        }
    }

    suspend fun saveLastProcessedBoundaryTimestamp(timestamp: Long) {
        context.dataStore.updateData { preferences ->
            preferences.toMutablePreferences().apply {
                this[LAST_PROCESSED_BOUNDARY_TIMESTAMP] = timestamp
            }
        }
    }

    suspend fun clearAll() {
        context.dataStore.edit { it.clear() }
    }

    suspend fun setDeviceTotalSnapshotsRaw(raw: String) {
        context.dataStore.edit { prefs ->
            prefs[DEVICE_TOTAL_SNAPSHOTS] = raw
        }
    }

    suspend fun resetBackfillRanges() {
        context.dataStore.edit { prefs ->
            prefs[LAST_PROCESSED_RANGE_START] = 0L
            prefs[LAST_PROCESSED_RANGE_END] = 0L
            prefs[LAST_PROCESSED_BOUNDARY_TIMESTAMP] = 0L
        }
    }

    suspend fun tryClaimBackfillRange(start: Long, end: Long): Boolean {
        var allowed = false
        context.dataStore.edit { prefs ->
            val savedStart = prefs[LAST_PROCESSED_RANGE_START] ?: 0L
            val savedEnd = prefs[LAST_PROCESSED_RANGE_END] ?: 0L
            val overlaps = start <= savedEnd && end >= savedStart
            if (!overlaps) {
                prefs[LAST_PROCESSED_RANGE_START] = start
                prefs[LAST_PROCESSED_RANGE_END] = end
                prefs[LAST_PROCESSED_BOUNDARY_TIMESTAMP] = end
                allowed = true
            }
        }
        return allowed
    }

    suspend fun saveDeviceTotalSnapshot(timestamp: Long, deviceTotal: Int) {
        context.dataStore.edit { prefs ->
            val raw = prefs[DEVICE_TOTAL_SNAPSHOTS] ?: "[]"
            val snapshots = parseSnapshots(raw).toMutableList()
            snapshots.add(DeviceTotalSnapshot(timestamp = timestamp, deviceTotal = deviceTotal))
            val cutoff = timestamp - SNAPSHOT_RETENTION_MS
            val pruned = snapshots.filter { it.timestamp >= cutoff }.sortedBy { it.timestamp }
            prefs[DEVICE_TOTAL_SNAPSHOTS] = serializeSnapshots(pruned)
        }
    }

    suspend fun getDeviceTotalSnapshots(): List<DeviceTotalSnapshot> {
        val raw = context.dataStore.data.map { it[DEVICE_TOTAL_SNAPSHOTS] ?: "[]" }.first()
        return parseSnapshots(raw)
    }

    private fun parseSnapshots(raw: String): List<DeviceTotalSnapshot> {
        return try {
            val array = JSONArray(raw)
            val list = ArrayList<DeviceTotalSnapshot>(array.length())
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val timestamp = obj.optLong("timestamp", 0L)
                val deviceTotal = obj.optInt("deviceTotal", 0)
                if (timestamp > 0) {
                    list.add(DeviceTotalSnapshot(timestamp = timestamp, deviceTotal = deviceTotal))
                }
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun serializeSnapshots(snapshots: List<DeviceTotalSnapshot>): String {
        val array = JSONArray()
        for (snapshot in snapshots) {
            val obj = JSONObject()
            obj.put("timestamp", snapshot.timestamp)
            obj.put("deviceTotal", snapshot.deviceTotal)
            array.put(obj)
        }
        return array.toString()
    }
}
