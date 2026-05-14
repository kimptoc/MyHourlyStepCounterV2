package com.example.myhourlystepcounterv2.sensor

/**
 * Immutable data class representing the state of the step sensor manager.
 * This class holds all the state that was previously managed with ReentrantReadWriteLock.
 */
data class SensorState(
    val lastKnownStepCount: Int = 0,
    val lastHourStartStepCount: Int = 0,
    val isInitialized: Boolean = false,
    val previousSensorValue: Int = 0,
    val wasBelowThreshold: Boolean = false,
    val currentHourSteps: Int = 0,
    val hourTransitionInProgress: Boolean = false,
    val lastSensorEventTimeMs: Long = 0L,
    // Steps accumulated in the current hour before the most recent device reboot.
    // After reboot the TYPE_STEP_COUNTER restarts at 0 on this device, so we capture
    // the pre-reboot in-hour delta here and add it to every display/save calculation
    // until the next hour boundary clears it.
    val preRebootOffset: Int = 0
)
