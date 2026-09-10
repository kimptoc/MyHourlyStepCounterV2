package com.example.myhourlystepcounterv2

import com.example.myhourlystepcounterv2.data.StepPreferences
import com.example.myhourlystepcounterv2.sensor.StepSensorManager
import kotlinx.coroutines.flow.first

/**
 * Resolves the total counter value used to seed in-memory sensor state at startup.
 *
 * If we already have a fresh sensor callback, prefer live sensor values over cached
 * preferences to avoid inflating current-hour deltas with stale totals.
 */
fun resolveKnownTotalForInitialization(
    savedTotal: Int,
    baseline: Int,
    currentDeviceSteps: Int,
    hasFreshSensorEvent: Boolean
): Int {
    return if (hasFreshSensorEvent) {
        maxOf(baseline, currentDeviceSteps)
    } else {
        maxOf(savedTotal, baseline, currentDeviceSteps)
    }
}

/**
 * Reads the device's boot count, used to detect a reboot between two readings. Both
 * [com.example.myhourlystepcounterv2.services.StepCounterForegroundService] and
 * [com.example.myhourlystepcounterv2.ui.StepCounterViewModel] call this one function — not a
 * copy each — so their cold-start paths cannot silently drift apart on what a reboot looks like.
 */
fun getCurrentBootCount(contentResolver: android.content.ContentResolver): Int {
    return try {
        android.provider.Settings.Global.getInt(contentResolver, android.provider.Settings.Global.BOOT_COUNT)
    } catch (_: Exception) {
        -1
    }
}

/**
 * Restores a persisted pre-reboot step offset into sensor state on a same-hour cold start.
 *
 * Both [com.example.myhourlystepcounterv2.services.StepCounterForegroundService] and
 * [com.example.myhourlystepcounterv2.ui.StepCounterViewModel] can be the first to seed the
 * sensor for a given hour, so this lives here rather than as a copy in each — the same reason
 * [getCurrentBootCount] does.
 *
 * Doesn't log the offset itself — every caller already folds it into its own seed-summary
 * log line, so a log here would just double it up.
 */
suspend fun restorePersistedPreRebootOffset(
    preferences: StepPreferences,
    sensorManager: StepSensorManager
): Int {
    val persistedOffset = preferences.currentHourPreRebootOffset.first()
    if (persistedOffset > 0) {
        sensorManager.setPreRebootOffset(persistedOffset)
    }
    return persistedOffset
}

/**
 * Clears a pre-reboot step offset that no longer applies because the hour it belonged to is
 * over. Shared for the same reason [restorePersistedPreRebootOffset] is: both cold-start entry
 * points fall back to this when a hand-off to [com.example.myhourlystepcounterv2.services.HourBoundaryCloser]
 * doesn't advance the saved hour.
 */
suspend fun clearStalePreRebootOffset(
    preferences: StepPreferences,
    sensorManager: StepSensorManager,
    logTag: String
): Int {
    val staleOffset = preferences.currentHourPreRebootOffset.first()
    if (staleOffset > 0) {
        preferences.saveCurrentHourPreRebootOffset(0)
        sensorManager.setPreRebootOffset(0)
        android.util.Log.i(logTag, "Cleared stale preRebootOffset=$staleOffset (hour changed)")
    }
    return staleOffset
}
