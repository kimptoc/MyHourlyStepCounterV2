package com.example.myhourlystepcounterv2.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.myhourlystepcounterv2.data.StepDatabase
import com.example.myhourlystepcounterv2.data.StepPreferences
import com.example.myhourlystepcounterv2.data.StepRepository
import com.example.myhourlystepcounterv2.sensor.StepSensorManager
import com.example.myhourlystepcounterv2.StepTrackerConfig
import kotlinx.coroutines.flow.first
import java.util.Calendar

/**
 * WorkManager job that runs periodically to check for missed hour boundaries.
 * This serves as a backup to the foreground service and AlarmManager to ensure
 * that hour boundaries are processed even if those systems fail.
 */
class HourBoundaryCheckWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return try {
            val database = StepDatabase.getDatabase(applicationContext)
            val repository = StepRepository(database.stepDao())
            val preferences = StepPreferences(applicationContext)
            val sensorManager = StepSensorManager.getInstance(applicationContext)

            // Get current hour timestamp
            val currentHourTimestamp = Calendar.getInstance().apply {
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis

            // Get the last processed hour timestamp from preferences
            val lastProcessedHour = preferences.currentHourTimestamp.first()

            // Check if any hour boundaries were missed
            if (lastProcessedHour > 0 && lastProcessedHour < currentHourTimestamp) {
                val hoursDifference = (currentHourTimestamp - lastProcessedHour) / (60 * 60 * 1000).toLong()

                if (hoursDifference > 0) {
                    android.util.Log.w(
                        "HourBoundaryCheckWorker",
                        "Detected $hoursDifference missed hour boundaries. " +
                                "Last processed: ${java.util.Date(lastProcessedHour)}, " +
                                "Current: ${java.util.Date(currentHourTimestamp)}"
                    )

                    // Get current device total from sensor
                    val currentDeviceTotal = sensorManager.getCurrentTotalSteps()
                    val fallbackTotal = preferences.totalStepsDevice.first()
                    val deviceTotal = if (currentDeviceTotal > 0) currentDeviceTotal else fallbackTotal

                    // Calculate steps taken during the missed period
                    val lastKnownSteps = preferences.totalStepsDevice.first()
                    val totalStepsDuringGap = deviceTotal - lastKnownSteps

                    if (totalStepsDuringGap > 0) {
                        // Distribute steps across missed hours
                        val stepsPerHour = (totalStepsDuringGap / hoursDifference).toInt()

                        // Save steps for each missed hour
                        var hourTimestamp = lastProcessedHour
                        for (i in 0 until hoursDifference.toInt()) {
                            if (i < hoursDifference.toInt() - 1) {  // Don't process the current hour
                                val stepsForHour = minOf(stepsPerHour, StepTrackerConfig.MAX_STEPS_PER_HOUR)

                                // Skip hours that are in the future (shouldn't happen but just in case)
                                if (hourTimestamp < currentHourTimestamp) {
                                    repository.saveHourlySteps(hourTimestamp, stepsForHour)
                                    android.util.Log.d(
                                        "HourBoundaryCheckWorker",
                                        "Retroactively saved $stepsForHour steps for hour: ${java.util.Date(hourTimestamp)}"
                                    )
                                }

                                // Move to next hour
                                hourTimestamp += (60 * 60 * 1000).toLong()
                            }
                        }
                    }

                    // Update preferences with the current hour and step count
                    preferences.saveHourData(
                        hourStartStepCount = deviceTotal,
                        currentTimestamp = currentHourTimestamp,
                        totalSteps = deviceTotal
                    )

                    android.util.Log.i(
                        "HourBoundaryCheckWorker",
                        "Completed processing of $hoursDifference missed hour boundaries"
                    )
                }
            } else {
                android.util.Log.d(
                    "HourBoundaryCheckWorker",
                    "No missed hour boundaries detected. Last processed: ${java.util.Date(lastProcessedHour)}, " +
                            "Current: ${java.util.Date(currentHourTimestamp)}"
                )
            }

            Result.success()
        } catch (e: Exception) {
            android.util.Log.e("HourBoundaryCheckWorker", "Error checking for missed hour boundaries", e)
            Result.retry()
        }
    }
}