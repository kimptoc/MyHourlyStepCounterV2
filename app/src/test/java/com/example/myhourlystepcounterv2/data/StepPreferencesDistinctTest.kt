package com.example.myhourlystepcounterv2.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
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
        val emissions = mutableListOf<Long>()
        val job = launch {
            preferences.currentHourTimestamp.collect { emissions.add(it) }
        }

        // Subscribe before the writes so any spurious re-emission lands while we watch.
        withTimeoutOrNull(2000L) {
            while (emissions.isEmpty()) delay(5)
        }

        // DataStore re-publishes the whole snapshot on every write. Before the fix,
        // currentHourTimestamp defaulted to a fresh System.currentTimeMillis() on each
        // emission, so these unrelated writes forced new values downstream.
        preferences.saveTotalStepsDevice(100)
        preferences.saveReminderSentThisHour(true)
        preferences.saveLastKnownBootCount(7)

        // Give DataStore a bounded window to deliver any spurious re-emission.
        delay(100)

        job.cancelAndJoin()

        assertEquals(
            "Unrelated preference writes must not re-emit currentHourTimestamp",
            1,
            emissions.size
        )
    }
}