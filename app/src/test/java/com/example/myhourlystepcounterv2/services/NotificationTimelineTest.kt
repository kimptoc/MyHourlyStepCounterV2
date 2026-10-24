package com.example.myhourlystepcounterv2.services

import com.example.myhourlystepcounterv2.services.StepCounterForegroundService.Companion.buildTimelineSummary
import com.example.myhourlystepcounterv2.services.StepCounterForegroundService.Companion.countedTimelineHours
import com.example.myhourlystepcounterv2.services.StepCounterForegroundService.Companion.timelineCircleHours
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The persistent notification's timeline row: which hours get a circle, and which hours the
 * "🎯 x/y hit" summary covers. The circles are the last 8 hours ending at the current hour;
 * the summary covers every hour of the 08:00–22:00 active window so far.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NotificationTimelineTest {

    private val goal = 250

    @Test
    fun beforeActiveWindow_noCirclesAndNoCountedHours() {
        assertTrue(timelineCircleHours(7).isEmpty())
        assertTrue(countedTimelineHours(7).isEmpty())

        val summary = buildTimelineSummary(7, goal, 500, isSyncing = false) { 500 }
        assertEquals(emptyList<String>(), summary.states)
        assertEquals(0, summary.achievedHours)
        assertEquals(0, summary.elapsedHours)
    }

    @Test
    fun at0800_firstCircleAppears() {
        // 07:59 is hour 7 (nothing yet); from 08:00 to 08:59 the current hour is the only circle.
        assertTrue(timelineCircleHours(7).isEmpty())
        assertEquals(8..8, timelineCircleHours(8))
    }

    @Test
    fun at0900_circlesEndAtCurrentHourWithNoLookahead() {
        assertEquals(8..9, timelineCircleHours(9))
        assertEquals(8..9, countedTimelineHours(9))

        val summary = buildTimelineSummary(9, goal, 100, isSyncing = false) { 300 }
        assertEquals(listOf("🟢", "🟡"), summary.states)
        assertEquals(1, summary.achievedHours)
        assertEquals(2, summary.elapsedHours)
    }

    @Test
    fun at2100_circlesAreLastEightHours_hitsCoverWholeDay() {
        assertEquals(14..21, timelineCircleHours(21))
        assertEquals(8..21, countedTimelineHours(21))

        // Every past hour hit except 10:00 (outside the circles) and 15:00 (inside them).
        val summary = buildTimelineSummary(21, goal, 300, isSyncing = false) { hour ->
            if (hour == 10 || hour == 15) 0 else 300
        }
        assertEquals(listOf("🟢", "❌", "🟢", "🟢", "🟢", "🟢", "🟢", "🟢"), summary.states)
        assertEquals(12, summary.achievedHours)
        assertEquals(14, summary.elapsedHours)
    }

    @Test
    fun at2200_windowClosed_showsLastEightCompletedHours_andFullDayTotal() {
        assertEquals(14..21, timelineCircleHours(22))
        assertEquals(8..21, countedTimelineHours(22))

        val summary = buildTimelineSummary(22, goal, 0, isSyncing = false) { 300 }
        assertEquals(List(8) { "🟢" }, summary.states)
        assertEquals(14, summary.achievedHours)
        assertEquals(14, summary.elapsedHours)
    }

    @Test
    fun lateNight_staysOnLastActiveHours() {
        assertEquals(14..21, timelineCircleHours(23))
        assertEquals(8..21, countedTimelineHours(23))
    }

    @Test
    fun syncingCurrentHour_showsHourglass_andIsNotCountedAsHit() {
        val summary = buildTimelineSummary(10, goal, 999, isSyncing = true) { 300 }
        assertEquals(listOf("🟢", "🟢", "⏳"), summary.states)
        assertEquals(2, summary.achievedHours)
        assertEquals(3, summary.elapsedHours)
    }

    @Test
    fun currentHourAtGoal_isGreenAndCounted() {
        val summary = buildTimelineSummary(8, goal, goal, isSyncing = false) { 0 }
        assertEquals(listOf("🟢"), summary.states)
        assertEquals(1, summary.achievedHours)
        assertEquals(1, summary.elapsedHours)
    }
}
