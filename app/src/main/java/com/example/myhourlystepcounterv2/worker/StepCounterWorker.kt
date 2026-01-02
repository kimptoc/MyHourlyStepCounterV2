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
            val preferencesTotalSteps = preferences.totalStepsDevice.first()

            // Try to get current device steps from sensor (in case app was just closed before sensor updated preferences)
            // This handles edge case: app closes immediately, then hour boundary crosses, preferences still has stale value
            var currentDeviceSteps = preferencesTotalSteps
            try {
                val sensorMgr = applicationContext.getSystemService(android.content.Context.SENSOR_SERVICE)
                    as android.hardware.SensorManager
                val stepSensor = sensorMgr.getDefaultSensor(android.hardware.Sensor.TYPE_STEP_COUNTER)

                // Try to read sensor with short timeout
                if (stepSensor != null && hasPermission) {
                    val sensorHelper = com.example.myhourlystepcounterv2.sensor.StepSensorManager(applicationContext)
                    sensorHelper.startListening()

                    // Wait up to 500ms for sensor to fire
                    for (i in 0 until 5) {
                        val sensorValue = sensorHelper.getCurrentTotalSteps()
                        if (sensorValue > 0) {
                            currentDeviceSteps = sensorValue
                            android.util.Log.d("StepCounterWorker", "Successfully read sensor value: $sensorValue")
                            break
                        }
                        Thread.sleep(100)
                    }
                    sensorHelper.stopListening()

                    // If sensor reading differs from preferences, log the difference
                    if (currentDeviceSteps != preferencesTotalSteps) {
                        android.util.Log.w(
                            "StepCounterWorker",
                            "Sensor value ($currentDeviceSteps) differs from preferences ($preferencesTotalSteps). " +
                                    "Using sensor value (app may have been closed before preferences updated)."
                        )
                    }
                }
            } catch (e: Exception) {
                android.util.Log.d("StepCounterWorker", "Could not read sensor, using preferences value: ${e.message}")
                currentDeviceSteps = preferencesTotalSteps
            }

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
