package com.example.myhourlystepcounterv2.worker

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object WorkManagerScheduler {
    private const val STEP_COUNTER_WORK = "step_counter_work"

    fun scheduleHourlyStepCounter(context: Context) {
        val stepCounterWork = PeriodicWorkRequestBuilder<StepCounterWorker>(
            1, // repeat interval
            TimeUnit.HOURS
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
