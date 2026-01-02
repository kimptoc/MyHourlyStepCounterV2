package com.example.myhourlystepcounterv2.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.myhourlystepcounterv2.PermissionHelper
import com.example.myhourlystepcounterv2.data.StepDatabase
import com.example.myhourlystepcounterv2.data.StepEntity
import com.example.myhourlystepcounterv2.data.StepPreferences
import com.example.myhourlystepcounterv2.data.StepRepository
import kotlinx.coroutines.flow.first
import java.util.Calendar

class StepCounterWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return try {
            val database = StepDatabase.getDatabase(applicationContext)
            val repository = StepRepository(database.stepDao())
            val preferences = StepPreferences(applicationContext)

            // Check permission status for logging
            val hasPermission = PermissionHelper.hasActivityRecognitionPermission(applicationContext)
            if (!hasPermission) {
                android.util.Log.w(
                    "StepCounterWorker",
                    "ACTIVITY_RECOGNITION permission not granted - sensor data may not be available"
                )
            }

            // CRITICAL: Use cached device total from preferences, NOT sensor
            // Reason: Background sensor access is unreliable/restricted on modern Android.
            // The foreground ViewModel continuously updates preferences.totalStepsDevice
            // whenever the sensor fires, so it's always current and reliable.
            // Worker attempts to register + immediately read sensor = race condition (gets 0/stale)

            // Get the previous hour's timestamp (the hour that just completed)
            val calendar = Calendar.getInstance().apply {
                add(Calendar.HOUR_OF_DAY, -1)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val previousHourTimestamp = calendar.timeInMillis

            // Get the current hour's timestamp (the hour that's starting now)
            val currentHourTimestamp = Calendar.getInstance().apply {
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis

            // Get stored data for the hour that just completed
            val hourStartStepCount = preferences.hourStartStepCount.first()
            val currentDeviceSteps = preferences.totalStepsDevice.first()

            android.util.Log.d(
                "StepCounterWorker",
                "Hour boundary: hourStart=$hourStartStepCount, current=$currentDeviceSteps"
            )

            // Calculate steps during the completed hour using cached preference value
            val stepsInPreviousHour = if (currentDeviceSteps > 0 && hourStartStepCount > 0) {
                val delta = currentDeviceSteps - hourStartStepCount

                // Validate delta
                val validatedDelta = when {
                    delta < 0 -> {
                        android.util.Log.w(
                            "StepCounterWorker",
                            "Negative delta ($delta). Sensor may have reset. Using 0."
                        )
                        0
                    }
                    delta > 10000 -> {
                        android.util.Log.w(
                            "StepCounterWorker",
                            "Unreasonable delta ($delta). Clamping to 10000."
                        )
                        10000
                    }
                    else -> delta
                }
                validatedDelta
            } else if (hourStartStepCount == 0 && currentDeviceSteps == 0) {
                // First run or no data yet
                0
            } else if (hourStartStepCount > currentDeviceSteps) {
                // Sensor reset between hours
                android.util.Log.w(
                    "StepCounterWorker",
                    "Sensor reset detected (start=$hourStartStepCount > current=$currentDeviceSteps)"
                )
                0
            } else {
                0
            }

            android.util.Log.d(
                "StepCounterWorker",
                "Saving $stepsInPreviousHour steps for hour at $previousHourTimestamp"
            )

            // Save the completed hour's data
            val previousHourRecord = StepEntity(
                timestamp = previousHourTimestamp,
                stepCount = maxOf(0, stepsInPreviousHour)
            )
            repository.saveHourlySteps(previousHourRecord.timestamp, previousHourRecord.stepCount)

            // Update preferences for the new hour that's starting
            preferences.saveHourData(
                hourStartStepCount = currentDeviceSteps,
                currentTimestamp = currentHourTimestamp,
                totalSteps = currentDeviceSteps
            )

            android.util.Log.d(
                "StepCounterWorker",
                "New hour initialized: timestamp=$currentHourTimestamp, startSteps=$currentDeviceSteps"
            )

            Result.success()
        } catch (e: Exception) {
            android.util.Log.e("StepCounterWorker", "Error in doWork", e)
            e.printStackTrace()
            Result.retry()
        }
    }
}
