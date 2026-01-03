package com.example.myhourlystepcounterv2

/**
 * Application configuration constants for step tracking behavior.
 * These values define how the app handles various edge cases and data processing.
 */
object StepTrackerConfig {
    /**
     * Hour threshold for distinguishing early morning from later in the day.
     * Used when app reopens after closure:
     * - If reopened before this hour: assume first-open-of-day, put all steps in current hour
     * - If reopened at or after this hour: distribute steps evenly across hours since closure
     *
     * Value: 10 (10:00 AM)
     */
    const val MORNING_THRESHOLD_HOUR = 10

    /**
     * Maximum reasonable step count per hour.
     * Steps exceeding this are clamped to this value (likely due to sensor resets or sync issues).
     *
     * Value: 10,000 steps/hour
     */
    const val MAX_STEPS_PER_HOUR = 10000

    /**
     * String representations for display
     */
    const val MORNING_THRESHOLD_DISPLAY = "10:00 AM"
    const val MAX_STEPS_DISPLAY = "10,000"
}
