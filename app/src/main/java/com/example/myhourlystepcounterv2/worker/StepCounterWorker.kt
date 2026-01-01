package com.example.myhourlystepcounterv2.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.myhourlystepcounterv2.data.StepDatabase
import com.example.myhourlystepcounterv2.data.StepEntity
import com.example.myhourlystepcounterv2.data.StepPreferences
import com.example.myhourlystepcounterv2.data.StepRepository
import com.example.myhourlystepcounterv2.sensor.StepSensorManager
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

            val sensorManager = StepSensorManager(applicationContext)
            sensorManager.startListening()

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
            val deviceTotalSteps = preferences.totalStepsDevice.first()

            // Get current step count from sensor
            val currentDeviceSteps = sensorManager.getCurrentTotalSteps()

            // Calculate steps during the completed hour
            val stepsInPreviousHour = if (deviceTotalSteps > 0) {
                currentDeviceSteps - hourStartStepCount
            } else {
                // First run, no previous data
                0
            }

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

            sensorManager.stopListening()
            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            Result.retry()
        }
    }
}
