package com.example.myhourlystepcounterv2.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cover for fabricated hourly values, built from the real device_total_snapshots_json ledger
 * pulled off the phone on 2026-09-08. That day the 03:00 hour was saved as 10000 steps (the
 * MAX_STEPS_PER_HOUR clamp) while every snapshot from 01:57 to 08:04 read a flat 263188 — the
 * counter had not moved at all, so the value was fabricated rather than merely implausible.
 *
 * The detector must fire on that hour and stay silent on every other hour of the same day,
 * including the hours where real walking happened.
 */
class StepAnomalyDetectorTest {

    /** Real ledger, "epochMillis:deviceTotal" pairs, 2026-09-07 23:30 -> 2026-09-08 13:11 local. */
    private val realLedgerRaw: String =
        "1788820300574:263079,1788820602635:263079,1788820905684:263079,1788821207735:263079," +
        "1788821510766:263079,1788821812806:263079,1788822114874:263079,1788822416930:263079," +
        "1788822720003:263079,1788823022072:263079,1788823325124:263079,1788823627162:263079," +
        "1788823930200:263079,1788824232242:263079,1788824535287:263079,1788824837328:263079," +
        "1788825140396:263079,1788825442444:263079,1788825744492:263079,1788826046527:263079," +
        "1788826349570:263079,1788826651611:263079,1788826954666:263079,1788827256705:263079," +
        "1788827559748:263079,1788827861966:263079,1788828165041:263079,1788828467103:263079," +
        "1788828767164:263121,1788829069660:263188,1788829371715:263188,1788829673771:263188," +
        "1788829976822:263188,1788830278879:263188,1788830581929:263188,1788830883957:263188," +
        "1788831186993:263188,1788831620093:263188,1788831923216:263188,1788832225284:263188," +
        "1788832528357:263188,1788832830437:263188,1788833133475:263188,1788833435539:263188," +
        "1788833738594:263188,1788834040648:263188,1788834435164:263188,1788834738269:263188," +
        "1788835040341:263188,1788835343377:263188,1788835645428:263188,1788835948480:263188," +
        "1788836250531:263188,1788836552576:263188,1788836854604:263188,1788837157670:263188," +
        "1788837459718:263188,1788837762767:263188,1788838064796:263188,1788838367834:263188," +
        "1788838669883:263188,1788838972938:263188,1788839274991:263188,1788839578038:263188," +
        "1788839880073:263188,1788840182113:263188,1788840484161:263188,1788840787184:263188," +
        "1788841089233:263188,1788841392295:263188,1788841694344:263188,1788841997391:263188," +
        "1788842299442:263188,1788842602494:263188,1788842904543:263188,1788843207596:263188," +
        "1788843509640:263188,1788843811698:263188,1788844113753:263188,1788844416803:263188," +
        "1788844718851:263188,1788845021899:263188,1788845323942:263188,1788845626998:263188," +
        "1788845929049:263188,1788846232094:263188,1788846534138:263188,1788846837182:263188," +
        "1788847139231:263188,1788847441280:263188,1788847743329:263188,1788848046383:263188," +
        "1788848348430:263188,1788848651481:263188,1788848953535:263188,1788849256598:263188," +
        "1788849558664:263188,1788849861731:263188,1788850163801:263188,1788850466847:263188," +
        "1788850768899:263188,1788851070921:263188,1788851512744:263188,1788851814818:263188," +
        "1788852114865:263249,1788852414899:263733,1788853085914:263936,1788853872536:263936," +
        "1788854174585:263936,1788854477645:263936,1788854779689:263936,1788855082733:263936," +
        "1788855384816:263936,1788855686892:263936,1788855988962:263936,1788856413704:263936," +
        "1788856715748:263936,1788857018805:263936,1788857320870:263936,1788857685942:263936," +
        "1788858189355:264692,1788859015284:264909,1788859317345:264909,1788859620389:264909," +
        "1788859922433:264909,1788860540274:264909,1788861176008:264909,1788861478026:264909," +
        "1788861778049:264956,1788862080077:265241,1788862382125:265241,1788863111764:265241," +
        "1788863752571:265241,1788864054645:265241,1788864357708:265241,1788864659751:265241," +
        "1788864961778:265268,1788865263832:265268,1788865928961:265297,1788866592734:265511," +
        "1788866892759:265625,1788867192779:265710,1788867492816:265765,1788867948594:265825," +
        "1788868565039:265880,1788868867096:265880,1788869215711:265880,1788869517744:265880,"

    private fun realLedger(): List<DeviceTotalSnapshot> =
        realLedgerRaw.split(",").filter { it.isNotBlank() }.map { pair ->
            val (ts, total) = pair.split(":")
            DeviceTotalSnapshot(timestamp = ts.trim().toLong(), deviceTotal = total.trim().toInt())
        }

    /** 2026-09-08 03:00:00 local (BST) — the hour that was saved as a phantom 10000. */
    private val phantomHourStart = 1788832800000L

    @Test
    fun corroboratedBound_isZeroForTheFlatOvernightHour() {
        val bound = StepAnomalyDetector.corroboratedBound(phantomHourStart, realLedger())

        assertEquals(0, bound!!.delta)
    }

    /** What the DB actually held for 2026-09-08, hour-of-day to saved step count. */
    private val savedThatDay = mapOf(
        0 to 0, 1 to 109, 2 to 0, 3 to 10000, 4 to 0, 5 to 0, 6 to 0,
        7 to 0, 8 to 748, 9 to 0, 10 to 491, 11 to 359, 12 to 612
    )

    /** 2026-09-08 00:00:00 local (BST). */
    private val startOfDay = 1788822000000L

    private fun hourStart(hourOfDay: Int): Long = startOfDay + hourOfDay * 3_600_000L

    @Test
    fun isAnomalous_firesOnTheFabricatedOvernightHour() {
        val bound = StepAnomalyDetector.corroboratedBound(phantomHourStart, realLedger())!!

        assertTrue(StepAnomalyDetector.isAnomalous(savedSteps = 10000, bound = bound))
    }

    @Test
    fun isAnomalous_staysSilentOnEveryOtherHourOfThatDay() {
        val ledger = realLedger()

        val firing = savedThatDay.keys.filter { hourOfDay ->
            val bound = StepAnomalyDetector.corroboratedBound(hourStart(hourOfDay), ledger)
            bound != null && StepAnomalyDetector.isAnomalous(savedThatDay.getValue(hourOfDay), bound)
        }

        assertEquals(listOf(3), firing)
    }

    @Test
    fun isAnomalous_staysSilentWhenSnapshotsStalledInDeepSleep() {
        // Counter moved 0 across the bracketed samples, but a 40-minute hole means the ledger
        // simply was not watching. Under-reporting is not evidence of fabrication.
        val stalled = listOf(
            DeviceTotalSnapshot(phantomHourStart - 60_000L, 263188),
            DeviceTotalSnapshot(phantomHourStart + 2_400_000L, 263188),
            DeviceTotalSnapshot(phantomHourStart + 3_660_000L, 263188)
        )
        val bound = StepAnomalyDetector.corroboratedBound(phantomHourStart, stalled)!!

        assertFalse(StepAnomalyDetector.isAnomalous(savedSteps = 10000, bound = bound))
    }

    @Test
    fun isAnomalous_toleratesStepsFallingBetweenTheLastSnapshotAndTheHourEdge() {
        val bound = CorroboratedBound(delta = 100, maxSnapshotGapMs = 300_000L)

        assertFalse(StepAnomalyDetector.isAnomalous(savedSteps = 140, bound = bound))
    }

    @Test
    fun corroboratedBound_isNullWhenTheLedgerDoesNotBracketTheHour() {
        val onlyBefore = listOf(DeviceTotalSnapshot(phantomHourStart - 60_000L, 263188))

        assertNull(StepAnomalyDetector.corroboratedBound(phantomHourStart, onlyBefore))
    }





    @Test
    fun corroboratedBound_isNullWhenTheCounterWentBackwards() {
        // A reboot or a health-app reseed drops the counter. That is not "the counter provably
        // moved zero" — it is "this hour cannot be judged". Clamping it to 0 would accuse a
        // legitimate hour of inventing every step in it.
        val afterReset = listOf(
            DeviceTotalSnapshot(phantomHourStart - 60_000L, 263188),
            DeviceTotalSnapshot(phantomHourStart + 1_800_000L, 40),
            DeviceTotalSnapshot(phantomHourStart + 3_660_000L, 412)
        )

        assertNull(StepAnomalyDetector.corroboratedBound(phantomHourStart, afterReset))
    }

    // --- Deferred sweep -------------------------------------------------------------
    // The write that creates a phantom happens at the hour boundary, before the ledger has
    // any snapshot past that boundary, so the hour cannot be bracketed at write time. These
    // assert the detector works on the ledger as it exists LATER, which is the only time the
    // evidence is actually available.

    private fun storedHoursForThatDay(): List<StepEntity> =
        savedThatDay.map { (hourOfDay, steps) ->
            StepEntity(timestamp = hourStart(hourOfDay), stepCount = steps, createdAt = 0L)
        }

    @Test
    fun sweep_catchesThePhantomOnceTheLedgerHasMovedPastTheBoundary() {
        // 05:00 — by now the 04:02 snapshot exists, so the 03:00 hour is bracketed at last.
        val sweepTime = hourStart(5)
        val ledgerSoFar = realLedger().filter { it.timestamp <= sweepTime }

        val found = StepAnomalyDetector.sweepCompletedHours(
            storedHours = storedHoursForThatDay(),
            snapshots = ledgerSoFar,
            sourcePathByHour = mapOf(phantomHourStart to "handleHourBoundary"),
            alreadyRecorded = emptySet(),
            now = sweepTime,
            detectedAt = sweepTime
        )

        assertEquals(listOf(phantomHourStart), found.map { it.hourTimestamp })
        assertEquals(10000, found.single().savedSteps)
        assertEquals("handleHourBoundary", found.single().sourcePath)
    }

    @Test
    fun sweep_isSilentAtTheBoundaryItselfBecauseTheHourIsNotBracketedYet() {
        // 04:00:02 — the moment the phantom was actually written. Nothing to judge against.
        val writeTime = 1788836402000L
        val ledgerSoFar = realLedger().filter { it.timestamp <= writeTime }

        val found = StepAnomalyDetector.sweepCompletedHours(
            storedHours = storedHoursForThatDay(),
            snapshots = ledgerSoFar,
            sourcePathByHour = emptyMap(),
            alreadyRecorded = emptySet(),
            now = writeTime,
            detectedAt = writeTime
        )

        assertTrue(found.none { it.hourTimestamp == phantomHourStart })
    }

    @Test
    fun sweep_doesNotReportAnHourAlreadyRecorded() {
        val sweepTime = hourStart(5)

        val found = StepAnomalyDetector.sweepCompletedHours(
            storedHours = storedHoursForThatDay(),
            snapshots = realLedger().filter { it.timestamp <= sweepTime },
            sourcePathByHour = emptyMap(),
            alreadyRecorded = setOf(phantomHourStart),
            now = sweepTime,
            detectedAt = sweepTime
        )

        assertTrue(found.isEmpty())
    }

    @Test
    fun sweep_skipsTheHourStillInProgress() {
        // The current hour's row is a partial checkpoint by definition; judging it would
        // flag every in-flight hour.
        val now = hourStart(12) + 1_800_000L
        val inProgress = listOf(StepEntity(timestamp = hourStart(12), stepCount = 9999, createdAt = 0L))

        val found = StepAnomalyDetector.sweepCompletedHours(
            storedHours = inProgress,
            snapshots = realLedger(),
            sourcePathByHour = emptyMap(),
            alreadyRecorded = emptySet(),
            now = now,
            detectedAt = now
        )

        assertTrue(found.isEmpty())
    }

    @Test
    fun sweep_labelsAnHourWithNoRecordedWriterAsUnknown() {
        val sweepTime = hourStart(5)

        val found = StepAnomalyDetector.sweepCompletedHours(
            storedHours = storedHoursForThatDay(),
            snapshots = realLedger().filter { it.timestamp <= sweepTime },
            sourcePathByHour = emptyMap(),
            alreadyRecorded = emptySet(),
            now = sweepTime,
            detectedAt = sweepTime
        )

        assertEquals("unknown", found.single().sourcePath)
    }
}
