package com.example.myhourlystepcounterv2.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import com.example.myhourlystepcounterv2.data.StepPreferences
import com.example.myhourlystepcounterv2.data.StepRepository
import com.example.myhourlystepcounterv2.data.StepDatabase
import com.example.myhourlystepcounterv2.sensor.StepSensorManager
import java.util.Calendar

class HourBoundaryReceiver(
    private var stepPreferences: StepPreferences? = null
) : BroadcastReceiver() {
    companion object {
        const val ACTION_HOUR_BOUNDARY = "com.example.myhourlystepcounterv2.ACTION_HOUR_BOUNDARY"
        const val ACTION_BOUNDARY_CHECK = "com.example.myhourlystepcounterv2.ACTION_BOUNDARY_CHECK"
    }

    override fun onReceive(context: Context, intent: Intent) {
        android.util.Log.i("HourBoundary", "onReceive called! action=${intent.action}")

        if (stepPreferences == null) {
            stepPreferences = StepPreferences(context.applicationContext)
        }

        when (intent.action) {
            ACTION_HOUR_BOUNDARY -> {
                android.util.Log.i("HourBoundary", "Hour boundary alarm triggered at ${Calendar.getInstance().time}")
                processHourBoundary(context)
            }
            ACTION_BOUNDARY_CHECK -> {
                android.util.Log.i("HourBoundary", "Boundary check alarm triggered at ${Calendar.getInstance().time}")
                checkForMissedBoundaries(context)
            }
            else -> {
                android.util.Log.w("HourBoundary", "Unknown action received: ${intent.action}, ignoring")
                return
            }
        }
    }

    /**
     * Periodic check to detect and process missed hour boundaries
     * This is a backup safety net that runs every 15 minutes
     */
    private fun checkForMissedBoundaries(context: Context) {
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.Default + SupervisorJob()).launch {
            try {
                // Calculate current hour timestamp
                val currentHourTimestamp = Calendar.getInstance().apply {
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis

                val preferences = stepPreferences ?: StepPreferences(context.applicationContext)
                val previousHourTimestamp = preferences.currentHourTimestamp.first()
                val lastProcessed = preferences.lastProcessedBoundaryTimestamp.first()

                // Deduplication: Skip if THIS hour was already processed
                if (currentHourTimestamp <= lastProcessed) {
                    android.util.Log.d("HourBoundary", "✓ CHECK: Current hour $currentHourTimestamp already processed, skipping missed check")
                    return@launch
                }

                // Check if we're past the saved hour
                if (previousHourTimestamp > 0 && previousHourTimestamp < currentHourTimestamp) {
                    val hoursDifference = (currentHourTimestamp - previousHourTimestamp) / (60 * 60 * 1000)

                    android.util.Log.w(
                        "HourBoundary",
                        "🔍 CHECK: Detected $hoursDifference missed hour(s)! Last saved: ${java.util.Date(previousHourTimestamp)}, current: ${java.util.Date(currentHourTimestamp)}"
                    )

                    // Trigger hour boundary processing
                    processHourBoundary(
                        context,
                        isBackupCheck = true,
                        existingPendingResult = pendingResult
                    )
                } else {
                    android.util.Log.d(
                        "HourBoundary",
                        "✓ CHECK: No missed boundaries. Current hour: ${java.util.Date(currentHourTimestamp)}, saved: ${java.util.Date(previousHourTimestamp)}"
                    )
                }

                // Reschedule next check in 15 minutes
                AlarmScheduler.scheduleBoundaryCheckAlarm(context.applicationContext)
            } catch (e: Exception) {
                android.util.Log.e("HourBoundary", "Error checking for missed boundaries", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    /**
     * Process the hour boundary transition
     */
    private fun processHourBoundary(
        context: Context,
        isBackupCheck: Boolean = false,
        existingPendingResult: BroadcastReceiver.PendingResult? = null
    ) {
        android.util.Log.i("HourBoundary", if (isBackupCheck) "Processing missed boundary (backup check)" else "Processing hour boundary (scheduled)")

        // Use goAsync() to extend BroadcastReceiver lifecycle for coroutine work
        val pendingResult = existingPendingResult ?: goAsync()

        CoroutineScope(Dispatchers.Default + SupervisorJob()).launch {
            try {
                val sensorManager = StepSensorManager.getInstance(context.applicationContext)
                val preferences = stepPreferences ?: StepPreferences(context.applicationContext)
                val database = StepDatabase.getDatabase(context.applicationContext)
                val repository = StepRepository(database.stepDao())

                // Calculate current hour timestamp
                val currentHourTimestamp = Calendar.getInstance().apply {
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis

                // Get the PREVIOUS hour's data that needs to be saved
                val previousHourTimestamp = preferences.currentHourTimestamp.first()
                val lastProcessed = preferences.lastProcessedBoundaryTimestamp.first()

                // Deduplication: Skip if THIS hour was already processed
                if (currentHourTimestamp <= lastProcessed) {
                    android.util.Log.d("HourBoundary", "processHourBoundary: Current hour $currentHourTimestamp already processed, skipping")
                    return@launch
                }

                val previousHourStartStepCount = preferences.hourStartStepCount.first()

                // Defense in depth: Check if we missed multiple hour boundaries
                if (previousHourTimestamp > 0 && previousHourTimestamp < currentHourTimestamp) {
                    val hoursDifference = (currentHourTimestamp - previousHourTimestamp) / (60 * 60 * 1000)
                    if (hoursDifference > 1) {
                        android.util.Log.w(
                            "HourBoundary",
                            "⚠️ Detected $hoursDifference missed hours! Last saved: ${java.util.Date(previousHourTimestamp)}, current: ${java.util.Date(currentHourTimestamp)}"
                        )
                        // Continue to save what we can with the data we have
                        // The foreground service's checkMissedHourBoundaries() will handle distribution if needed
                    }
                }

                // Get current device total from sensor (or fallback to preferences)
                val currentDeviceTotal = sensorManager.getCurrentTotalSteps()
                val fallbackTotal = preferences.totalStepsDevice.first()

                val deviceTotal = if (currentDeviceTotal > 0) {
                    currentDeviceTotal
                } else if (fallbackTotal > 0) {
                    android.util.Log.w(
                        "HourBoundary",
                        "Sensor returned 0, using preferences fallback: $fallbackTotal"
                    )
                    fallbackTotal
                } else {
                    android.util.Log.e(
                        "HourBoundary",
                        "CRITICAL: Both sensor and preferences returned 0. Cannot process hour boundary safely. Aborting."
                    )
                    return@launch
                }

                // Calculate steps in the PREVIOUS hour (that just ended)
                var stepsInPreviousHour = deviceTotal - previousHourStartStepCount

                // Validate and clamp the value
                val MAX_STEPS_PER_HOUR = 10000
                if (stepsInPreviousHour < 0) {
                    android.util.Log.w("HourBoundary", "Negative step delta ($stepsInPreviousHour). Clamping to 0.")
                    stepsInPreviousHour = 0
                } else if (stepsInPreviousHour > MAX_STEPS_PER_HOUR) {
                    android.util.Log.w("HourBoundary", "Unreasonable step delta ($stepsInPreviousHour). Clamping to $MAX_STEPS_PER_HOUR.")
                    stepsInPreviousHour = MAX_STEPS_PER_HOUR
                }

                // Mark as processed BEFORE async operations to prevent races
                // Store the CURRENT boundary timestamp to prevent double processing
                preferences.saveLastProcessedBoundaryTimestamp(currentHourTimestamp)

                // Save the completed previous hour to database
                android.util.Log.i(
                    "HourBoundary",
                    "Saving completed hour: timestamp=$previousHourTimestamp, steps=$stepsInPreviousHour (device=$deviceTotal - baseline=$previousHourStartStepCount)"
                )
                repository.saveHourlySteps(previousHourTimestamp, stepsInPreviousHour)

                android.util.Log.i(
                    "HourBoundary",
                    "Processing hour boundary: deviceTotal=$deviceTotal, newHourTimestamp=$currentHourTimestamp"
                )

                // Begin hour transition - blocks sensor events from interfering
                sensorManager.beginHourTransition()

                try {
                    // Reset sensor for new hour (updates display to 0)
                    val resetSuccessful = sensorManager.resetForNewHour(deviceTotal)

                    if (!resetSuccessful) {
                        android.util.Log.w("HourBoundary", "Baseline already set, skipping duplicate reset")
                        return@launch
                    }

                    // Update preferences with new hour baseline
                    preferences.saveHourData(
                        hourStartStepCount = deviceTotal,
                        currentTimestamp = currentHourTimestamp,
                        totalSteps = deviceTotal
                    )

                    // Reset reminder/achievement flags for new hour
                    preferences.saveReminderSentThisHour(false)
                    preferences.saveAchievementSentThisHour(false)

                    android.util.Log.i(
                        "HourBoundary",
                        "✓ Hour boundary processed: Saved $stepsInPreviousHour steps, reset to baseline=$deviceTotal, display=0"
                    )
                } finally {
                    // End hour transition - resume sensor events
                    sensorManager.endHourTransition()
                }

                // Reschedule the next alarm (1 hour from now at XX:00)
                AlarmScheduler.scheduleHourBoundaryAlarms(context.applicationContext)
                android.util.Log.d("HourBoundary", "Rescheduled next alarm for 1 hour from now")
            } catch (e: Exception) {
                android.util.Log.e("HourBoundary", "Error processing hour boundary", e)
            } finally {
                pendingResult?.finish()
            }
        }
    }
}
