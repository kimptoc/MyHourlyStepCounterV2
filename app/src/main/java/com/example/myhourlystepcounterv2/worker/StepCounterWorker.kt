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

            // Check if ViewModel recently completed closure distribution
            // If so, defer to avoid race condition (2 min grace period)
            val lastDistributionTime = preferences.lastDistributionTime.first()
            val timeSinceDistribution = System.currentTimeMillis() - lastDistributionTime
            val DISTRIBUTION_GRACE_PERIOD_MS = 2 * 60 * 1000  // 2 minutes

            if (timeSinceDistribution < DISTRIBUTION_GRACE_PERIOD_MS && lastDistributionTime > 0) {
                android.util.Log.w(
                    "StepCounterWorker",
                    "ViewModel distribution completed ${timeSinceDistribution / 1000}s ago - deferring WorkManager save to avoid conflicts"
                )
                // Distribution happened recently - let ViewModel's values settle
                // Just update preferences for next hour and exit
                preferences.saveHourData(
                    hourStartStepCount = preferencesTotalSteps,
                    currentTimestamp = currentHourTimestamp,
                    totalSteps = preferencesTotalSteps
                )
                return Result.success()
            }

            android.util.Log.d(
                "StepCounterWorker",
                "DIAGNOSTIC: Retrieved from preferences - hourStart=$hourStartStepCount, totalDevice=$preferencesTotalSteps"
            )

            // Try to get current device steps from sensor (in case app was just closed before sensor updated preferences)
            // This handles edge case: app closes immediately, then hour boundary crosses, preferences still has stale value
            var currentDeviceSteps = preferencesTotalSteps
            var sensorReadSuccessful = false
            try {
                val sensorMgr = applicationContext.getSystemService(android.content.Context.SENSOR_SERVICE)
                    as android.hardware.SensorManager
                val stepSensor = sensorMgr.getDefaultSensor(android.hardware.Sensor.TYPE_STEP_COUNTER)

                android.util.Log.d(
                    "StepCounterWorker",
                    "DIAGNOSTIC: Sensor available=${stepSensor != null}, hasPermission=$hasPermission"
                )

                // Try to read sensor with longer timeout for background context
                if (stepSensor != null && hasPermission) {
                    val sensorHelper = com.example.myhourlystepcounterv2.sensor.StepSensorManager.getInstance(applicationContext)

                    android.util.Log.d("StepCounterWorker", "DIAGNOSTIC: Reading from singleton sensor manager...")

                    // Read from singleton (should already be listening if app is alive)
                    // Wait up to 2000ms for sensor to have valid data (longer timeout for background)
                    for (i in 0 until 20) {
                        val sensorValue = sensorHelper.getCurrentTotalSteps()
                        android.util.Log.d(
                            "StepCounterWorker",
                            "DIAGNOSTIC: Retry $i: sensorValue=$sensorValue"
                        )
                        if (sensorValue > 0) {
                            currentDeviceSteps = sensorValue
                            sensorReadSuccessful = true
                            android.util.Log.d("StepCounterWorker", "Successfully read sensor value: $sensorValue on retry $i")
                            break
                        }
                        Thread.sleep(100)
                    }
                    // Don't stop listening - singleton is shared with other components

                    if (!sensorReadSuccessful) {
                        android.util.Log.w(
                            "StepCounterWorker",
                            "DIAGNOSTIC: Sensor did not fire after 2000ms timeout"
                        )
                    }

                    // If sensor reading differs from preferences, log the difference
                    if (currentDeviceSteps != preferencesTotalSteps) {
                        android.util.Log.w(
                            "StepCounterWorker",
                            "Sensor value ($currentDeviceSteps) differs from preferences ($preferencesTotalSteps). " +
                                    "Using sensor value (app may have been closed before preferences updated)."
                        )
                    }
                } else {
                    android.util.Log.w(
                        "StepCounterWorker",
                        "DIAGNOSTIC: Cannot read sensor - sensor=${stepSensor != null}, permission=$hasPermission"
                    )
                }
            } catch (e: Exception) {
                android.util.Log.w(
                    "StepCounterWorker",
                    "Could not read sensor, using preferences value: ${e.message}",
                    e
                )
                currentDeviceSteps = preferencesTotalSteps
            }

            android.util.Log.d(
                "StepCounterWorker",
                "Hour boundary: hourStart=$hourStartStepCount, current=$currentDeviceSteps, sensorSuccess=$sensorReadSuccessful"
            )

            // Calculate steps during the completed hour
            val stepsInPreviousHour = when {
                currentDeviceSteps > 0 && hourStartStepCount > 0 -> {
                    // Normal case: both values available, calculate delta
                    val delta = currentDeviceSteps - hourStartStepCount

                    android.util.Log.d(
                        "StepCounterWorker",
                        "DIAGNOSTIC: Normal delta calculation: $currentDeviceSteps - $hourStartStepCount = $delta"
                    )

                    // Validate delta
                    when {
                        delta < 0 -> {
                            android.util.Log.w(
                                "StepCounterWorker",
                                "DIAGNOSTIC: Negative delta ($delta). Sensor reset detected. Saving 0."
                            )
                            0
                        }
                        delta > 10000 -> {
                            android.util.Log.w(
                                "StepCounterWorker",
                                "DIAGNOSTIC: Unreasonable delta ($delta). Possible sensor glitch. Clamping to 10000."
                            )
                            10000
                        }
                        else -> {
                            android.util.Log.d(
                                "StepCounterWorker",
                                "DIAGNOSTIC: Valid delta: $delta steps"
                            )
                            delta
                        }
                    }
                }

                hourStartStepCount == 0 && currentDeviceSteps == 0 -> {
                    // Both zero: First run ever, no sensor data, or device just booted
                    android.util.Log.w(
                        "StepCounterWorker",
                        "DIAGNOSTIC: Both values are 0. Could be first run, no sensor data, or fresh boot. Saving 0."
                    )
                    0
                }

                hourStartStepCount == 0 && currentDeviceSteps > 0 -> {
                    // Missing baseline: App was killed before preferences were initialized
                    android.util.Log.w(
                        "StepCounterWorker",
                        "DIAGNOSTIC: MISSING BASELINE - hourStart=0 but sensor=$currentDeviceSteps. " +
                                "App was likely killed before hour started. Cannot calculate delta. Saving 0 for this hour. " +
                                "Will initialize baseline=$currentDeviceSteps for next hour."
                    )
                    0
                }

                hourStartStepCount > 0 && currentDeviceSteps == 0 -> {
                    // Sensor unavailable: Can't read sensor in background
                    android.util.Log.w(
                        "StepCounterWorker",
                        "DIAGNOSTIC: SENSOR UNAVAILABLE - hourStart=$hourStartStepCount but sensor=0. " +
                                "Cannot read sensor in background. Saving 0 for this hour."
                    )
                    0
                }

                hourStartStepCount > currentDeviceSteps -> {
                    // Sensor reset between hours (e.g., device rebooted)
                    android.util.Log.w(
                        "StepCounterWorker",
                        "DIAGNOSTIC: SENSOR RESET - start=$hourStartStepCount > current=$currentDeviceSteps. " +
                                "Device likely rebooted. Saving 0 for this hour."
                    )
                    0
                }

                else -> {
                    // Shouldn't reach here, but defensive programming
                    android.util.Log.e(
                        "StepCounterWorker",
                        "DIAGNOSTIC: UNEXPECTED STATE - hourStart=$hourStartStepCount, current=$currentDeviceSteps. " +
                                "Saving 0 for safety."
                    )
                    0
                }
            }

            android.util.Log.d(
                "StepCounterWorker",
                "DIAGNOSTIC: Preparing to save - previousHour=$previousHourTimestamp, steps=$stepsInPreviousHour"
            )

            // Check if this hour has already been saved by the ViewModel
            // This prevents race condition where both ViewModel and WorkManager save the same hour
            val existingRecord = database.stepDao().getStepForHour(previousHourTimestamp)

            if (existingRecord != null) {
                val savedTime = java.text.SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss",
                    java.util.Locale.getDefault()
                ).format(java.util.Date(previousHourTimestamp))

                android.util.Log.i(
                    "StepCounterWorker",
                    "⚠️ SKIPPING SAVE: Hour $savedTime already has ${existingRecord.stepCount} steps " +
                            "(saved by ViewModel). WorkManager would have saved $stepsInPreviousHour. " +
                            "Keeping ViewModel's value to avoid race condition."
                )
            } else {
                // Hour not yet saved - save our calculated value
                // This handles case where app was killed and ViewModel never ran
                val previousHourRecord = StepEntity(
                    timestamp = previousHourTimestamp,
                    stepCount = maxOf(0, stepsInPreviousHour)
                )
                repository.saveHourlySteps(previousHourRecord.timestamp, previousHourRecord.stepCount)

                val savedTime = java.text.SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss",
                    java.util.Locale.getDefault()
                ).format(java.util.Date(previousHourTimestamp))

                android.util.Log.i(
                    "StepCounterWorker",
                    "✓ SAVED: Hour $savedTime → $stepsInPreviousHour steps (ViewModel did not save, app may have been killed)"
                )
            }

            // Check if preferences already have a baseline for this hour (from ViewModel running at hour boundary)
            val existingHourStart = preferences.hourStartStepCount.first()
            val existingHourTimestamp = preferences.currentHourTimestamp.first()

            // Only update preferences if we're moving to a different hour OR baseline not yet set
            if (existingHourTimestamp != currentHourTimestamp || existingHourStart == 0) {
                // This is truly a new hour, update preferences
                preferences.saveHourData(
                    hourStartStepCount = currentDeviceSteps,
                    currentTimestamp = currentHourTimestamp,
                    totalSteps = currentDeviceSteps
                )

                android.util.Log.i(
                    "StepCounterWorker",
                    "✓ UPDATED PREFERENCES for new hour: baseline=$currentDeviceSteps"
                )

                // Update singleton to match
                val sensorManager = com.example.myhourlystepcounterv2.sensor.StepSensorManager.getInstance(applicationContext)
                sensorManager.updateHourBaselineIfNeeded(currentDeviceSteps)
            } else {
                // ViewModel already set correct baseline for this hour, don't overwrite!
                android.util.Log.i(
                    "StepCounterWorker",
                    "✓ PRESERVED EXISTING BASELINE: Preferences already have baseline=$existingHourStart for current hour. " +
                    "Not overwriting with WorkManager's value ($currentDeviceSteps) to preserve mid-hour progress."
                )

                // Still update singleton to match preferences (in case app was restarted)
                val sensorManager = com.example.myhourlystepcounterv2.sensor.StepSensorManager.getInstance(applicationContext)
                sensorManager.updateHourBaselineIfNeeded(existingHourStart)
            }

            Result.success()
        } catch (e: Exception) {
            android.util.Log.e("StepCounterWorker", "Error in doWork", e)
            e.printStackTrace()
            Result.retry()
        }
    }
}
