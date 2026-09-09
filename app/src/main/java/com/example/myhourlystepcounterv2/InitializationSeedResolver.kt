package com.example.myhourlystepcounterv2

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
 * Reads the device's boot count, used to detect a reboot between two readings. Shared by
 * [com.example.myhourlystepcounterv2.services.StepCounterForegroundService] and
 * [com.example.myhourlystepcounterv2.ui.StepCounterViewModel] so both cold-start paths agree on
 * what a reboot looks like.
 */
fun getCurrentBootCount(contentResolver: android.content.ContentResolver): Int {
    return try {
        android.provider.Settings.Global.getInt(contentResolver, android.provider.Settings.Global.BOOT_COUNT)
    } catch (_: Exception) {
        -1
    }
}
