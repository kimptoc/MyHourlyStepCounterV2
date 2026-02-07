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
    val lastSensorEventTimeMs: Long = 0L
)