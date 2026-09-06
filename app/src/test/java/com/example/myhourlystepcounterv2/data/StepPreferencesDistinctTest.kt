package com.example.myhourlystepcounterv2.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class StepPreferencesDistinctTest {
    private lateinit var context: Context
    private lateinit var preferences: StepPreferences

    @Before
    fun setUp() {
        runBlocking {
            context = ApplicationProvider.getApplicationContext()
            preferences = StepPreferences(context)
            preferences.clearAll()
            preferences.resetBackfillRanges()
            preferences.setDeviceTotalSnapshotsRaw("[]")
        }
    }

    @Test
    fun unrelatedWrites_doNotReemitCurrentHourTimestamp() = runBlocking {
        val firstValue = preferences.currentHourTimestamp.first()

        // DataStore re-publishes the whole snapshot on every write. Before the fix,
        // currentHourTimestamp defaulted to a fresh System.currentTimeMillis() on each
        // emission, so unrelated writes forced a new value downstream.
        preferences.saveTotalStepsDevice(100)
        preferences.saveReminderSentThisHour(true)
        preferences.saveLastKnownBootCount(7)

        // With distinctUntilChanged (and a stable 0L default) the flow never re-emits a
        // different value, so this times out and returns null.
        val differentEmission = withTimeoutOrNull(1000L) {
            preferences.currentHourTimestamp
                .dropWhile { it == firstValue }
                .first()
        }

        assertNull(
            "Unrelated preference writes must not re-emit currentHourTimestamp",
            differentEmission
        )
    }
}
