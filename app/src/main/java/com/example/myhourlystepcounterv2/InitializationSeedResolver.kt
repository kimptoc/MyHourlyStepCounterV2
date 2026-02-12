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
