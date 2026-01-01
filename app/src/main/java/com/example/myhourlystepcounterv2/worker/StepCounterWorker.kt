package com.example.myhourlystepcounterv2.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.myhourlystepcounterv2.data.StepDatabase
import com.example.myhourlystepcounterv2.data.StepRepository
import com.example.myhourlystepcounterv2.sensor.StepSensorManager
import java.util.Calendar

class StepCounterWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return try {
            val database = StepDatabase.getDatabase(applicationContext)
            val repository = StepRepository(database.stepDao())

            val sensorManager = StepSensorManager(applicationContext)
            sensorManager.startListening()

            // Get the current hour's start timestamp
            val calendar = Calendar.getInstance().apply {
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val hourStartTimestamp = calendar.timeInMillis

            // Get current step count from sensor
            val currentSteps = sensorManager.getCurrentTotalSteps()

            // Check if we already have a record for this hour
            val existingRecord = repository.getStepForHour(hourStartTimestamp)

            if (existingRecord == null) {
                // New hour, get the previous hour's steps
                val previousHourTimestamp = hourStartTimestamp - (60 * 60 * 1000)
                val previousRecord = repository.getStepForHour(previousHourTimestamp)

                // Calculate steps in the previous hour
                val stepsInPreviousHour = if (previousRecord != null) {
                    currentSteps - previousRecord.stepCount
                } else {
                    // First hour, we don't know the delta
                    0
                }

                // Save the previous hour's data
                repository.saveHourlySteps(previousHourTimestamp, stepsInPreviousHour)
            }

            sensorManager.stopListening()
            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            Result.retry()
        }
    }
}
