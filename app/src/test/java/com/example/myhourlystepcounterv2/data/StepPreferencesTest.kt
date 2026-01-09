package com.example.myhourlystepcounterv2.data

import org.junit.Assert.assertTrue
import org.junit.Test

class StepPreferencesTest {
    @Test
    fun testDefaultPreferencesEnabled() {
        // Defaults should be ON as requested
        assertTrue("Permanent notification default should be true", StepPreferences.PERMANENT_NOTIFICATION_DEFAULT)
        assertTrue("Use wake lock default should be true", StepPreferences.USE_WAKE_LOCK_DEFAULT)
    }
}
