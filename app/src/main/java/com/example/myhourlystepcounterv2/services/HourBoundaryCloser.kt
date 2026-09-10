package com.example.myhourlystepcounterv2.services

import com.example.myhourlystepcounterv2.StepTrackerConfig
import com.example.myhourlystepcounterv2.data.StepPreferences
import com.example.myhourlystepcounterv2.data.StepRepository
import com.example.myhourlystepcounterv2.sensor.StepSensorManager
import com.example.myhourlystepcounterv2.services.StepCounterForegroundService.Companion.BoundaryAction
import com.example.myhourlystepcounterv2.services.StepCounterForegroundService.Companion.FLUSH_THRESHOLD_MS
import com.example.myhourlystepcounterv2.services.StepCounterForegroundService.Companion.computeStepsForBoundarySave
import com.example.myhourlystepcounterv2.services.StepCounterForegroundService.Companion.confirmFreshSensorReadingWithFallback
import com.example.myhourlystepcounterv2.services.StepCounterForegroundService.Companion.isBackfillReferencePlausible
import com.example.myhourlystepcounterv2.services.StepCounterForegroundService.Companion.isDeviceRebootDetected
import com.example.myhourlystepcounterv2.services.StepCounterForegroundService.Companion.reconcileBoundarySaveWithDisplay
import com.example.myhourlystepcounterv2.services.StepCounterForegroundService.Companion.resolveBackfillHourSteps
import com.example.myhourlystepcounterv2.services.StepCounterForegroundService.Companion.resolveBackfillReferenceTotal
import com.example.myhourlystepcounterv2.services.StepCounterForegroundService.Companion.resolveBoundaryAction
import com.example.myhourlystepcounterv2.services.StepCounterForegroundService.Companion.resolvePreviousHourTimestamp
import com.example.myhourlystepcounterv2.services.StepCounterForegroundService.Companion.shouldBreakCounterContinuity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Closes a completed hour and seeds the new one — the missed-boundary decision, the ordinary
 * hourly switch, and the multi-hour backfill, all in one place.
 *
 * Shared between [StepCounterForegroundService] (the running boundary loop, the alarm-driven
 * check, and an OS-restarted cold start) and
 * [com.example.myhourlystepcounterv2.ui.StepCounterViewModel] (a launcher-tap cold start, which
 * runs whenever the app is opened after the process died — issue #25 part 2). Both can be the
 * first thing to observe a stale [StepPreferences.currentHourTimestamp] after a process death,
 * and whichever used to seed the new hour on its own first would silently drop the completed
 * hour's tail steps while the other saw `isInitialized == true` and skipped its own check
 * entirely. Routing both through this one class, coordinated by [mutex], removes that race —
 * and matters most when the user has disabled the persistent notification: with the service
 * never running and [com.example.myhourlystepcounterv2.worker.HourBoundaryCheckWorker] skipping
 * itself in that configuration, the ViewModel's cold start is the *only* thing that ever
 * resolves a missed boundary.
 *
 * The wake-lock and post-close hooks default to no-ops: only the foreground service holds a
 * wake lock and owns the persistent notification and alarm rescheduling, so it supplies real
 * implementations; the ViewModel's cold start runs with the app in the foreground and the
 * screen on, so neither is needed there. [onAlarmReschedule] mirrors the original
 * handleHourBoundaryLocked-only behavior: the backfill path only refreshes the notification,
 * it never rescheduled alarms.
 */
class HourBoundaryCloser(
    private val preferences: StepPreferences,
    private val sensorManager: StepSensorManager,
    private val repository: StepRepository,
    private val getCurrentBootCount: () -> Int,
    private val acquireWakeLock: suspend (String) -> Long? = { null },
    private val releaseWakeLock: (Long?, String) -> Unit = { _, _ -> },
    private val onNotificationRefresh: suspend () -> Unit = {},
    private val onAlarmReschedule: suspend () -> Unit = {},
    private val logTag: String = "HourBoundaryCloser"
) {
    companion object {
        /** Shared across every instance in the process — see the class KDoc. */
        val mutex = Mutex()

        @Volatile
        var lastProcessedBoundaryTimestamp: Long = 0
    }

    suspend fun checkMissedHourBoundaries() {
        mutex.withLock { checkMissedHourBoundariesLocked() }
    }

    /** Body of [checkMissedHourBoundaries]; the caller must already hold [mutex]. */
    /**
     * [onFreshConfirmAttempted] fires exactly once, right before the backfill branch attempts a
     * flush/re-register cycle -- used by [handleHourBoundaryLocked]'s own gapHours>1 nested call
     * to this function (issue #36 review) so it can skip its own redundant attempt against a
     * sensor this call just tried and failed to freshen, rather than paying the up-to-5s cost
     * twice under the same mutex hold.
     */
    suspend fun checkMissedHourBoundariesLocked(onFreshConfirmAttempted: () -> Unit = {}) {
        val wakeLockToken = acquireWakeLock("missed-boundary check")
        try {
            // Calculate current hour timestamp (what we're about to process)
            val currentHourTimestamp = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }.timeInMillis

            val savedHourTimestamp = preferences.currentHourTimestamp.first()
            val lastProcessed = preferences.lastProcessedBoundaryTimestamp.first()
            val effectiveLastProcessed = maxOf(lastProcessed, lastProcessedBoundaryTimestamp)

            // Both counter sources are offered, because the boundary handler falls back to
            // the saved total when the sensor has not reported in this process yet. Issue #25:
            // this path used to lose that race on a cold start, because the caller's own
            // seeding advanced the hour before this ever ran. Both cold-start callers now run
            // this before seeding, so this is the path that closes the completed hour.
            val action = resolveBoundaryAction(
                currentHourTimestamp = currentHourTimestamp,
                savedHourTimestamp = savedHourTimestamp,
                effectiveLastProcessed = effectiveLastProcessed,
                currentDeviceTotal = sensorManager.getCurrentTotalSteps(),
                savedDeviceTotal = preferences.totalStepsDevice.first(),
                rebootDetected = isDeviceRebootDetected(
                    currentBootCount = getCurrentBootCount(),
                    savedBootCount = preferences.lastKnownBootCount.first()
                )
            )

            when (action) {
                BoundaryAction.NONE -> {
                    android.util.Log.d(
                        logTag,
                        "checkMissedHourBoundaries: Nothing to close (current=${java.util.Date(currentHourTimestamp)}, " +
                                "saved=$savedHourTimestamp, effectiveLastProcessed=$effectiveLastProcessed), skipping"
                    )
                    return
                }
                BoundaryAction.DELEGATE_TO_HANDLER -> {
                    // No boundary was missed — this is just the hour ticking over, reached
                    // here because the alarm woke the device before the boundary loop's timer
                    // fired. Backfill would leave the completed hour on its partial checkpoint
                    // row and then mark the boundary processed, so the handler has to run.
                    android.util.Log.i(
                        logTag,
                        "checkMissedHourBoundaries: Saved hour ${java.util.Date(savedHourTimestamp)} is the hour that " +
                                "just completed (gap=1) — ordinary hourly switch, not a missed boundary. " +
                                "Delegating to the boundary handler so the hour's real total is saved."
                    )
                    handleHourBoundaryLocked()
                    return
                }
                BoundaryAction.BACKFILL -> { /* fall through to the backfill below */ }
            }

            val hoursDifference = (currentHourTimestamp - savedHourTimestamp) / (60 * 60 * 1000)
            val rangeStart = savedHourTimestamp
            val rangeEnd = currentHourTimestamp - (60 * 60 * 1000)

            val claimed = preferences.tryClaimBackfillRange(rangeStart, rangeEnd)
            if (!claimed) {
                android.util.Log.w(
                    logTag,
                    "checkMissedHourBoundaries: Backfill range already processed. start=${java.util.Date(rangeStart)}, end=${java.util.Date(rangeEnd)}"
                )
                return
            }

            android.util.Log.w(
                logTag,
                (if (hoursDifference == 1L) {
                    "Single-hour gap the hand-off refused (reboot, or no usable counter). "
                } else {
                    "Service restart detected: missed $hoursDifference hour boundaries. "
                }) + "Backfill range: ${java.util.Date(rangeStart)} -> ${java.util.Date(rangeEnd)}"
            )

            // Flush sensor FIFO before reading device total for backfill
            val sensorAgeForBackfill = System.currentTimeMillis() - sensorManager.getLastSensorEventTime()
            if (sensorAgeForBackfill > FLUSH_THRESHOLD_MS) {
                onFreshConfirmAttempted()
                android.util.Log.w(
                    logTag,
                    "checkMissedHourBoundaries: Sensor data stale (${sensorAgeForBackfill / 1000}s old). Flushing FIFO..."
                )
                confirmFreshSensorReadingWithFallback(
                    sensorState = sensorManager.sensorState,
                    doFlush = { sensorManager.flushSensor() },
                    doReRegister = { sensorManager.reRegisterListener() },
                    label = "checkMissedHourBoundaries",
                    logTag = logTag
                )
            }

            val currentDeviceTotal = sensorManager.getCurrentTotalSteps()
            val previousHourStartSteps = preferences.hourStartStepCount.first()
            val savedDeviceTotal = preferences.totalStepsDevice.first()
            val savedBootCount = preferences.lastKnownBootCount.first()
            val currentBootCount = getCurrentBootCount()
            val rebootDetected = isDeviceRebootDetected(currentBootCount, savedBootCount)

            val validSavedDeviceTotal = if (savedDeviceTotal == 0 && previousHourStartSteps > 0) {
                android.util.Log.w(
                    logTag,
                    "DETECTED BUG: savedDeviceTotal=0 but hourStartStepCount=$previousHourStartSteps. Using hourStartStepCount as fallback."
                )
                previousHourStartSteps
            } else {
                savedDeviceTotal
            }

            if (rebootDetected) {
                android.util.Log.w(
                    logTag,
                    "Device reboot detected (savedBootCount=$savedBootCount, currentBootCount=$currentBootCount). " +
                        "Breaking absolute-counter continuity for missed-hour backfill."
                )
            }

            val continuityBroken = shouldBreakCounterContinuity(
                currentDeviceTotal = currentDeviceTotal,
                savedDeviceTotal = validSavedDeviceTotal,
                rebootDetected = rebootDetected
            )
            if (continuityBroken) {
                android.util.Log.w(
                    logTag,
                    "Counter continuity broken (current=$currentDeviceTotal, saved=$validSavedDeviceTotal, reboot=$rebootDetected). " +
                        "Will preserve checkpointed data only and wait for post-boot baseline."
                )
            }

            val deviceTotalToUse = if (currentDeviceTotal > 0) {
                currentDeviceTotal
            } else if (rebootDetected) {
                android.util.Log.w(
                    logTag,
                    "Sensor not initialized after reboot (currentDeviceTotal=0). Avoiding stale fallback from pre-reboot total."
                )
                0
            } else if (validSavedDeviceTotal > 0) {
                android.util.Log.w(
                    logTag,
                    "Sensor not initialized yet (currentDeviceTotal=0), using fallback total=$validSavedDeviceTotal"
                )
                validSavedDeviceTotal
            } else {
                android.util.Log.e(
                    logTag,
                    "CRITICAL: Both currentDeviceTotal and savedDeviceTotal are 0. Cannot backfill safely."
                )
                0
            }

            val snapshots = preferences.getDeviceTotalSnapshots()
            // The device_total snapshot entering the missed window is the trustworthy
            // reference to subtract from — immune to a zeroed/stale saved total that
            // would otherwise make the whole lifetime count look like steps-while-closed.
            val referenceTotal = resolveBackfillReferenceTotal(
                savedDeviceTotal = validSavedDeviceTotal,
                snapshots = snapshots,
                rangeStart = rangeStart
            )
            val missedHourCount = ((rangeEnd - rangeStart) / (60 * 60 * 1000)).toInt() + 1
            val referencePlausible = isBackfillReferencePlausible(
                referenceTotal = referenceTotal,
                deviceTotalToUse = deviceTotalToUse,
                missedHourCount = missedHourCount,
                maxStepsPerHour = StepTrackerConfig.MAX_STEPS_PER_HOUR
            )

            val totalStepsWhileClosed = if (!continuityBroken && deviceTotalToUse > 0) {
                deviceTotalToUse - referenceTotal
            } else {
                0
            }
            if (totalStepsWhileClosed <= 0 || !referencePlausible) {
                android.util.Log.w(
                    logTag,
                    "Backfill: Skipping hour writes (totalStepsWhileClosed=$totalStepsWhileClosed, " +
                        "referenceTotal=$referenceTotal, deviceTotalToUse=$deviceTotalToUse, " +
                        "missedHourCount=$missedHourCount, plausible=$referencePlausible). " +
                        "Avoiding phantom steps from an untrustworthy reference total."
                )
            } else {
                val snapshotByHour = snapshots
                    .filter { it.timestamp in rangeStart until currentHourTimestamp }
                    .groupBy { ts ->
                        (ts.timestamp / (60 * 60 * 1000)) * (60 * 60 * 1000)
                    }
                    .mapValues { entry -> entry.value.maxByOrNull { it.timestamp }?.deviceTotal }

                val missingWithoutSnapshot = mutableListOf<Long>()
                var accountedSteps = 0
                var assignedSteps = 0
                var previousTotal = referenceTotal
                var hourCursor = rangeStart

                while (hourCursor <= rangeEnd) {
                    val existing = repository.getStepForHour(hourCursor)
                    val snapTotal = snapshotByHour[hourCursor]
                    // A stored row is not automatically final: the last hour of the range was
                    // in progress when the service went quiet, so its row is a partial
                    // checkpoint. Where snapshots bracket the hour, the measured delta wins.
                    val measuredSteps = resolveBackfillHourSteps(
                        existingSteps = existing?.stepCount,
                        snapshotTotal = snapTotal,
                        previousTotal = previousTotal,
                        maxStepsPerHour = StepTrackerConfig.MAX_STEPS_PER_HOUR
                    )
                    when {
                        measuredSteps != null -> {
                            if (existing != null) {
                                android.util.Log.i(
                                    logTag,
                                    "Backfill: Raising partial checkpoint for ${java.util.Date(hourCursor)} " +
                                            "from ${existing.stepCount} to measured $measuredSteps"
                                )
                                accountedSteps += measuredSteps
                            } else {
                                assignedSteps += measuredSteps
                            }
                            repository.saveHourlySteps(hourCursor, measuredSteps, sourcePath = "backfill")
                        }
                        existing != null -> accountedSteps += existing.stepCount
                        else -> missingWithoutSnapshot.add(hourCursor)
                    }
                    if (snapTotal != null && snapTotal >= previousTotal) {
                        previousTotal = snapTotal
                    }
                    hourCursor += (60 * 60 * 1000)
                }

                val remainingSteps = totalStepsWhileClosed - assignedSteps - accountedSteps
                if (missingWithoutSnapshot.isNotEmpty() && remainingSteps > 0) {
                    val stepsPerHour = remainingSteps / missingWithoutSnapshot.size
                    android.util.Log.i(
                        logTag,
                        "Backfill: Distributing remaining $remainingSteps steps across ${missingWithoutSnapshot.size} hours (~$stepsPerHour/hour)"
                    )
                    for (hourTs in missingWithoutSnapshot) {
                        val stepsClamped = minOf(stepsPerHour, StepTrackerConfig.MAX_STEPS_PER_HOUR)
                        repository.saveHourlySteps(hourTs, stepsClamped, sourcePath = "backfillDistribution")
                    }
                } else if (missingWithoutSnapshot.isNotEmpty()) {
                    android.util.Log.w(
                        logTag,
                        "Backfill: Remaining steps $remainingSteps <= 0. Skipping distribution for ${missingWithoutSnapshot.size} hours."
                    )
                }
            }

            sensorManager.beginHourTransition()
            try {
                if (deviceTotalToUse > 0) {
                    val resetSuccessful = sensorManager.resetForNewHour(deviceTotalToUse)
                    if (resetSuccessful) {
                        preferences.saveHourData(
                            hourStartStepCount = deviceTotalToUse,
                            currentTimestamp = currentHourTimestamp,
                            totalSteps = deviceTotalToUse
                        )
                        android.util.Log.i(
                            logTag,
                            "Preferences synced at missed boundary: baseline=$deviceTotalToUse, timestamp=$currentHourTimestamp, total=$deviceTotalToUse"
                        )
                        if (currentBootCount > 0) {
                            preferences.saveLastKnownBootCount(currentBootCount)
                        }
                        preferences.saveReminderSentThisHour(false)
                        preferences.saveSecondReminderSentThisHour(false)
                        preferences.saveAchievementSentThisHour(false)
                        // Backfill writes hourly rows for all missed hours including the
                        // saved hour, so any pre-reboot offset is now in DB. Clear it
                        // so it doesn't get re-added in the current hour.
                        preferences.saveCurrentHourPreRebootOffset(0)
                        // Mark processed last: everything this hour switch depends on (the
                        // hourly rows above, the new baseline/timestamp, the reset flags) is
                        // already durable by this point, so a crash before this line simply
                        // means the whole attempt safely retries rather than leaving a
                        // processed marker with no work behind it.
                        preferences.saveLastProcessedBoundaryTimestamp(currentHourTimestamp)
                        lastProcessedBoundaryTimestamp = currentHourTimestamp
                        android.util.Log.i(
                            logTag,
                            "Reset to current hour: baseline=$deviceTotalToUse, timestamp=$currentHourTimestamp, preRebootOffset cleared"
                        )
                    }
                } else {
                    android.util.Log.w(
                        logTag,
                        "Skipping hour reset - waiting for valid sensor reading"
                    )
                }
            } finally {
                sensorManager.endHourTransition()
            }

            syncStartOfDay()
            onNotificationRefresh()
        } catch (e: Exception) {
            android.util.Log.e(logTag, "Error checking missed hour boundaries", e)
        } finally {
            releaseWakeLock(wakeLockToken, "missed-boundary check")
        }
    }

    suspend fun handleHourBoundary() {
        mutex.withLock { handleHourBoundaryLocked() }
    }

    /**
     * Body of [handleHourBoundary]; the caller must already hold [mutex].
     * Save completed hour and reset for new hour.
     */
    suspend fun handleHourBoundaryLocked() {
        val wakeLockToken = acquireWakeLock("hour boundary")
        try {
            // Calculate current hour timestamp (what we're about to process)
            val currentHourTimestamp = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }.timeInMillis

            // Get the PREVIOUS hour's data that needs to be saved
            var previousHourTimestamp = preferences.currentHourTimestamp.first()
            val lastProcessed = preferences.lastProcessedBoundaryTimestamp.first()
            val effectiveLastProcessed = maxOf(lastProcessed, lastProcessedBoundaryTimestamp)

            // Deduplication: Skip if THIS hour was already processed
            if (currentHourTimestamp <= effectiveLastProcessed) {
                android.util.Log.d(
                    logTag,
                    "handleHourBoundary: Current hour $currentHourTimestamp already processed (effectiveLast=$effectiveLastProcessed), skipping"
                )
                return
            }

            val expectedPreviousHour = currentHourTimestamp - (60 * 60 * 1000)
            val gapHours = if (previousHourTimestamp > 0) {
                (currentHourTimestamp - previousHourTimestamp) / (60 * 60 * 1000)
            } else {
                0
            }

            var freshnessAlreadyAttempted = false
            if (gapHours > 1) {
                android.util.Log.w(
                    logTag,
                    "handleHourBoundary: Detected stale previousHourTimestamp=${java.util.Date(previousHourTimestamp)} " +
                            "(gap=$gapHours hours). Running missed-hour backfill before saving."
                )
                checkMissedHourBoundariesLocked(onFreshConfirmAttempted = { freshnessAlreadyAttempted = true })
                previousHourTimestamp = preferences.currentHourTimestamp.first()
                if (previousHourTimestamp < expectedPreviousHour || previousHourTimestamp > currentHourTimestamp) {
                    android.util.Log.w(
                        logTag,
                        "handleHourBoundary: Backfill did not advance hour timestamp (now=${java.util.Date(previousHourTimestamp)}). Will clamp to expected."
                    )
                }
            }

            val resolvedPreviousHour = resolvePreviousHourTimestamp(
                currentHourTimestamp = currentHourTimestamp,
                savedHourTimestamp = previousHourTimestamp
            )
            if (resolvedPreviousHour != previousHourTimestamp) {
                android.util.Log.w(
                    logTag,
                    "handleHourBoundary: Correcting previousHourTimestamp from ${java.util.Date(previousHourTimestamp)} " +
                            "to expected ${java.util.Date(expectedPreviousHour)}"
                )
                previousHourTimestamp = resolvedPreviousHour
            }

            val previousHourStartStepCount = preferences.hourStartStepCount.first()

            // Flush sensor FIFO to get latest step count before saving the hour.
            // During Doze, events may be batched in the hardware FIFO.
            val sensorAgeAtBoundary = System.currentTimeMillis() - sensorManager.getLastSensorEventTime()
            if (sensorAgeAtBoundary > FLUSH_THRESHOLD_MS) {
                if (freshnessAlreadyAttempted) {
                    // The nested checkMissedHourBoundariesLocked() call above already ran a full
                    // flush/re-register cycle against this same still-stale sensor a moment ago
                    // (issue #36 review) -- retrying identically here would just pay up to
                    // another 5s under this mutex for a mechanism that just failed.
                    android.util.Log.d(
                        logTag,
                        "handleHourBoundary: Skipping redundant flush/re-register; backfill already attempted this call"
                    )
                } else {
                    android.util.Log.w(
                        logTag,
                        "handleHourBoundary: Sensor data stale (${sensorAgeAtBoundary / 1000}s old). Flushing FIFO..."
                    )
                    confirmFreshSensorReadingWithFallback(
                        sensorState = sensorManager.sensorState,
                        doFlush = { sensorManager.flushSensor() },
                        doReRegister = { sensorManager.reRegisterListener() },
                        label = "handleHourBoundary",
                        logTag = logTag
                    )
                }
                val postAttemptAge = System.currentTimeMillis() - sensorManager.getLastSensorEventTime()
                android.util.Log.d(
                    logTag,
                    "handleHourBoundary: Post-attempt sensor age=${postAttemptAge / 1000}s"
                )
            }

            // Get current device total from sensor (or fallback to preferences)
            val currentDeviceTotal = sensorManager.getCurrentTotalSteps()
            val fallbackTotal = preferences.totalStepsDevice.first()

            val deviceTotal = if (currentDeviceTotal > 0) {
                currentDeviceTotal
            } else {
                android.util.Log.w(
                    logTag,
                    "Sensor returned 0, using preferences fallback: $fallbackTotal"
                )
                fallbackTotal
            }

            // Check for reboot or counter discontinuity before computing delta
            val savedBootCount = preferences.lastKnownBootCount.first()
            val currentBootCount = getCurrentBootCount()
            val rebootDetected = isDeviceRebootDetected(currentBootCount, savedBootCount)
            val continuityBroken = shouldBreakCounterContinuity(
                currentDeviceTotal = deviceTotal,
                savedDeviceTotal = previousHourStartStepCount,
                rebootDetected = rebootDetected
            )

            // Include any pre-reboot offset captured for the current hour. The offset
            // represents steps walked before the most recent reboot, which the sensor
            // counter (reset to 0 by reboot) cannot otherwise contribute.
            val preRebootOffset = preferences.currentHourPreRebootOffset.first()
            val stepsInPreviousHour = computeStepsForBoundarySave(
                deviceTotal = deviceTotal,
                baseline = previousHourStartStepCount,
                preRebootOffset = preRebootOffset,
                continuityBroken = continuityBroken,
                maxStepsPerHour = StepTrackerConfig.MAX_STEPS_PER_HOUR
            )
            if (continuityBroken) {
                android.util.Log.w(
                    logTag,
                    "handleHourBoundary: Counter continuity broken " +
                            "(device=$deviceTotal, baseline=$previousHourStartStepCount, reboot=$rebootDetected, offset=$preRebootOffset). " +
                            "Saving offset-only value $stepsInPreviousHour for previous hour."
                )
            } else if (preRebootOffset > 0) {
                android.util.Log.i(
                    logTag,
                    "handleHourBoundary: Including preRebootOffset=$preRebootOffset in hour save. " +
                        "Final stepsInPreviousHour=$stepsInPreviousHour"
                )
            }

            // Read the displayed count before the sensor is reset for the new hour. This is
            // the number the user saw for this hour in the notification and in any
            // goal-achieved alert; saving less than it is what makes the timeline marker
            // disagree with the notification that was posted minutes earlier.
            val displayedPreviousHourSteps = sensorManager.currentStepCount.value
            val stepsToSave = reconcileBoundarySaveWithDisplay(
                computedSteps = stepsInPreviousHour,
                displayedSteps = displayedPreviousHourSteps,
                continuityBroken = continuityBroken,
                maxStepsPerHour = StepTrackerConfig.MAX_STEPS_PER_HOUR
            )
            if (stepsToSave != stepsInPreviousHour) {
                android.util.Log.w(
                    logTag,
                    "handleHourBoundary: Displayed hour count ($displayedPreviousHourSteps) exceeds recomputed " +
                            "total ($stepsInPreviousHour). Saving $stepsToSave so the saved hour matches what was shown."
                )
            }

            // Save the completed previous hour to database
            android.util.Log.i(
                logTag,
                "Saving completed hour: timestamp=$previousHourTimestamp (${java.util.Date(previousHourTimestamp)}), steps=$stepsToSave (device=$deviceTotal - baseline=$previousHourStartStepCount, displayed=$displayedPreviousHourSteps)"
            )
            repository.saveHourlySteps(previousHourTimestamp, stepsToSave, sourcePath = "handleHourBoundary")

            android.util.Log.i(
                logTag,
                "Processing hour boundary: deviceTotal=$deviceTotal, newHourTimestamp=$currentHourTimestamp (${java.util.Date(currentHourTimestamp)})"
            )

            syncStartOfDay()

            // Begin hour transition - blocks sensor events from interfering
            sensorManager.beginHourTransition()

            try {
                // Reset sensor for new hour (updates display to 0)
                val resetSuccessful = sensorManager.resetForNewHour(deviceTotal)

                if (!resetSuccessful) {
                    android.util.Log.w(logTag, "Baseline already set in sensor, but still saving preferences")
                }

                // Update preferences with new hour baseline (always, even on duplicate sensor reset)
                preferences.saveHourData(
                    hourStartStepCount = deviceTotal,
                    currentTimestamp = currentHourTimestamp,
                    totalSteps = deviceTotal
                )
                val currentBootCount2 = getCurrentBootCount()
                if (currentBootCount2 > 0) {
                    preferences.saveLastKnownBootCount(currentBootCount2)
                }
                android.util.Log.i(
                    logTag,
                    "Preferences synced at hour boundary: baseline=$deviceTotal, timestamp=$currentHourTimestamp, total=$deviceTotal"
                )

                // Reset reminder/achievement flags for new hour
                preferences.saveReminderSentThisHour(false)
                preferences.saveSecondReminderSentThisHour(false)
                preferences.saveAchievementSentThisHour(false)

                // Clear pre-reboot offset: the hour it applied to has been saved.
                if (preRebootOffset > 0) {
                    preferences.saveCurrentHourPreRebootOffset(0)
                    // sensorManager.resetForNewHour above already cleared the in-memory copy
                }

                // Mark processed last. The hour row above, the new baseline/timestamp, and
                // the flag resets are all durable by this point, so a crash anywhere before
                // this line leaves nothing to recover — a fresh attempt safely redoes the
                // same work (saveHourlyStepsAtomic keeps the higher value, resetForNewHour
                // no-ops on a duplicate baseline) rather than a processed marker surviving
                // with no row behind it, which used to make this fallback the guaranteed
                // source of the #25 data loss instead of a rare race for it.
                preferences.saveLastProcessedBoundaryTimestamp(currentHourTimestamp)
                lastProcessedBoundaryTimestamp = currentHourTimestamp

                android.util.Log.i(
                    logTag,
                    "✓ Hour boundary processed: Saved $stepsToSave steps, reset to baseline=$deviceTotal, display=0, preRebootOffset cleared"
                )
            } finally {
                // End hour transition - resume sensor events
                sensorManager.endHourTransition()
            }

            onNotificationRefresh()
            onAlarmReschedule()
        } catch (e: Exception) {
            android.util.Log.e(logTag, "Error processing hour boundary", e)
        } finally {
            releaseWakeLock(wakeLockToken, "hour boundary")
        }
    }

    private suspend fun syncStartOfDay() {
        val currentStartOfDay = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis

        val storedStartOfDay = preferences.lastStartOfDay.first()
        if (storedStartOfDay == 0L || storedStartOfDay != currentStartOfDay) {
            val message = if (storedStartOfDay == 0L) {
                "Initializing lastStartOfDay to ${java.util.Date(currentStartOfDay)}"
            } else {
                "DAY BOUNDARY: Detected day change from ${java.util.Date(storedStartOfDay)} to ${java.util.Date(currentStartOfDay)}"
            }
            android.util.Log.i(logTag, message)
            preferences.saveStartOfDay(currentStartOfDay)
        }
    }
}
