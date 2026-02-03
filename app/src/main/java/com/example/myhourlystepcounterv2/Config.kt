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
     * Minimum step threshold for hourly step reminders.
     * User will receive a notification at :50 of each hour if below this threshold.
     * Reminds user to move before the hour ends.
     *
     * Value: 250 steps/hour
     */
    const val STEP_REMINDER_THRESHOLD = 250

    /**
     * Two-stage reminder system:
     * - First reminder at XX:50 (10 minutes before hour) with standard vibration
     * - Second reminder at XX:55 (5 minutes before hour) with enhanced vibration
     *   (only sent if steps still below threshold)
     */

    /**
     * Vibration pattern for first reminder (XX:50).
     * Pattern: [delay, vibrate, pause, vibrate]
     * Total duration: ~800ms with 2 vibrations
     */
    val FIRST_REMINDER_VIBRATION_PATTERN = longArrayOf(0, 300, 200, 300)

    /**
     * Vibration pattern for second reminder (XX:55).
     * More intense/urgent pattern with 4 vibrations instead of 2.
     * Pattern: [delay, vibrate, pause, vibrate, pause, vibrate, pause, vibrate]
     * Total duration: ~1600ms with 4 vibrations
     */
    val SECOND_REMINDER_VIBRATION_PATTERN = longArrayOf(0, 400, 200, 400, 200, 400, 200, 400)

    /**
     * Vibration pattern for urgent reminder channel (XX:55).
     * Triple buzz pattern: [delay, vibrate, pause, vibrate, pause, vibrate]
     * Total duration: ~900ms with 3 vibrations
     */
    val URGENT_REMINDER_VIBRATION_PATTERN = longArrayOf(0, 300, 150, 300, 150, 300)

    /**
     * String representations for display
     */
    const val MORNING_THRESHOLD_DISPLAY = "10:00 AM"
    const val MAX_STEPS_DISPLAY = "10,000"
}
