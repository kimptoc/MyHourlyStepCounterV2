package com.example.myhourlystepcounterv2.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class StepPreferencesBackfillTest {
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
    fun tryClaimBackfillRange_allowsNonOverlapping_andRejectsOverlap() = runBlocking {
        val savedStartBefore = preferences.lastProcessedRangeStart.first()
        val savedEndBefore = preferences.lastProcessedRangeEnd.first()
        assertEquals("Expected cleared range start", 0L, savedStartBefore)
        assertEquals("Expected cleared range end", 0L, savedEndBefore)

        val firstClaim = preferences.tryClaimBackfillRange(1000L, 2000L)
        assertTrue("First claim should succeed", firstClaim)

        val overlapClaim = preferences.tryClaimBackfillRange(1500L, 2500L)
        assertFalse("Overlapping claim should be rejected", overlapClaim)

        val secondClaim = preferences.tryClaimBackfillRange(3000L, 4000L)
        assertTrue("Second non-overlapping claim should succeed", secondClaim)

        val overlapSecond = preferences.tryClaimBackfillRange(3500L, 3600L)
        assertFalse("Overlapping with latest range should be rejected", overlapSecond)
    }

    @Test
    fun tryClaimBackfillRange_updatesSavedRange() = runBlocking {
        val claimed = preferences.tryClaimBackfillRange(5000L, 6000L)
        assertTrue(claimed)

        val savedStart = preferences.lastProcessedRangeStart.first()
        val savedEnd = preferences.lastProcessedRangeEnd.first()
        val savedBoundary = preferences.lastProcessedBoundaryTimestamp.first()

        assertEquals(5000L, savedStart)
        assertEquals(6000L, savedEnd)
        assertEquals(6000L, savedBoundary)
    }

    @Test
    fun snapshotParsing_returnsEmptyOnMalformedJson() = runBlocking {
        preferences.setDeviceTotalSnapshotsRaw("not-json")

        val snapshots = preferences.getDeviceTotalSnapshots()
        assertTrue(snapshots.isEmpty())
    }

    @Test
    fun snapshotRetention_prunesOlderThan24Hours() = runBlocking {
        val now = System.currentTimeMillis()
        val old = now - (25L * 60 * 60 * 1000)

        preferences.saveDeviceTotalSnapshot(old, 100)
        preferences.saveDeviceTotalSnapshot(now, 200)

        val snapshots = preferences.getDeviceTotalSnapshots()
        assertEquals(1, snapshots.size)
        assertEquals(200, snapshots.first().deviceTotal)
    }
}
