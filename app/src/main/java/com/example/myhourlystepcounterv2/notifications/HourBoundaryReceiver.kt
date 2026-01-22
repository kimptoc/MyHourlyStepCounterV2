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

class HourBoundaryReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_HOUR_BOUNDARY = "com.example.myhourlystepcounterv2.ACTION_HOUR_BOUNDARY"
    }

    override fun onReceive(context: Context, intent: Intent) {
        android.util.Log.i("HourBoundary", "onReceive called! action=${intent.action}, expected=$ACTION_HOUR_BOUNDARY")

        if (intent.action != ACTION_HOUR_BOUNDARY) {
            android.util.Log.w("HourBoundary", "Wrong action received, ignoring")
            return
        }

        android.util.Log.i("HourBoundary", "Hour boundary alarm triggered at ${Calendar.getInstance().time}")

        // Use goAsync() to extend BroadcastReceiver lifecycle for coroutine work
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.Default + SupervisorJob()).launch {
            try {
                val sensorManager = StepSensorManager.getInstance(context.applicationContext)
                val preferences = StepPreferences(context.applicationContext)
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
                } else {
                    android.util.Log.w(
                        "HourBoundary",
                        "Sensor returned 0, using preferences fallback: $fallbackTotal"
                    )
                    fallbackTotal
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
                pendingResult.finish()
            }
        }
    }
}
