package com.example.myhourlystepcounterv2.worker

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object WorkManagerScheduler {
    private const val STEP_COUNTER_WORK = "step_counter_work"
    /** Interval (in minutes) that WorkManager will use for the step counter periodic work. */
    const val STEP_COUNTER_INTERVAL_MINUTES = 15

    fun scheduleHourlyStepCounter(context: Context) {
        // WorkManager supports java.time.Duration overloads - be explicit to avoid overload ambiguity
        val stepCounterWork = PeriodicWorkRequestBuilder<StepCounterWorker>(
            java.time.Duration.ofMinutes(STEP_COUNTER_INTERVAL_MINUTES.toLong()),
            java.time.Duration.ofMinutes(5)
        ).build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            STEP_COUNTER_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            stepCounterWork
        )
    }

    fun cancelStepCounter(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(STEP_COUNTER_WORK)
    }
}
