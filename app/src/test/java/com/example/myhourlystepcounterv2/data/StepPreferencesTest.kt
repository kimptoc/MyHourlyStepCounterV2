package com.example.myhourlystepcounterv2.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StepPreferencesTest {
    @Test
    fun testDefaultPreferencesEnabled() {
        // Defaults should be ON as requested
        assertTrue("Permanent notification default should be true", StepPreferences.PERMANENT_NOTIFICATION_DEFAULT)
        assertTrue("Use wake lock default should be true", StepPreferences.USE_WAKE_LOCK_DEFAULT)
    }

    @Test
    fun testReminderOffsetDefaults() {
        assertEquals(10, StepPreferences.FIRST_REMINDER_MINUTES_BEFORE_DEFAULT)
        assertEquals(5, StepPreferences.SECOND_REMINDER_MINUTES_BEFORE_DEFAULT)
        assertTrue(
            StepPreferences.isValidReminderOffsets(
                StepPreferences.FIRST_REMINDER_MINUTES_BEFORE_DEFAULT,
                StepPreferences.SECOND_REMINDER_MINUTES_BEFORE_DEFAULT
            )
        )
    }

    @Test
    fun testReminderOffsetValidation() {
        assertTrue(StepPreferences.isValidReminderOffsets(59, 58))
        assertTrue(StepPreferences.isValidReminderOffsets(2, 1))
        assertTrue(StepPreferences.isValidReminderOffsets(30, 15))

        // Final alert must be strictly closer to the hour than the first warning
        assertFalse(StepPreferences.isValidReminderOffsets(5, 5))
        assertFalse(StepPreferences.isValidReminderOffsets(5, 10))
        // Out of range
        assertFalse(StepPreferences.isValidReminderOffsets(60, 5))
        assertFalse(StepPreferences.isValidReminderOffsets(1, 0))
        assertFalse(StepPreferences.isValidReminderOffsets(10, 0))
        assertFalse(StepPreferences.isValidReminderOffsets(0, -1))
    }
}
