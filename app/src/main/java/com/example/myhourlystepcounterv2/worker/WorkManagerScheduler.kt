package com.example.myhourlystepcounterv2.worker

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object WorkManagerScheduler {
    private const val STEP_COUNTER_WORK = "step_counter_work"
    private const val HOUR_BOUNDARY_CHECK_WORK = "hour_boundary_check_work"
    private const val HOUR_BOUNDARY_CHECK_ONCE = "hour_boundary_check_once"

    /** Interval (in minutes) that WorkManager will use for the step counter periodic work. */
    const val STEP_COUNTER_INTERVAL_MINUTES = 15

    /** Interval (in minutes) that WorkManager will use for the hour boundary check periodic work. */
    const val HOUR_BOUNDARY_CHECK_INTERVAL_MINUTES = 15

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

    fun scheduleHourBoundaryCheck(context: Context) {
        val hourBoundaryCheckWork = PeriodicWorkRequestBuilder<HourBoundaryCheckWorker>(
            java.time.Duration.ofMinutes(HOUR_BOUNDARY_CHECK_INTERVAL_MINUTES.toLong()),
            java.time.Duration.ofMinutes(5)
        ).build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            HOUR_BOUNDARY_CHECK_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            hourBoundaryCheckWork
        )
    }

    fun scheduleHourBoundaryCheckOnce(context: Context) {
        val work = OneTimeWorkRequestBuilder<HourBoundaryCheckWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            HOUR_BOUNDARY_CHECK_ONCE,
            ExistingWorkPolicy.REPLACE,
            work
        )
    }

    fun cancelStepCounter(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(STEP_COUNTER_WORK)
    }

    fun cancelHourBoundaryCheck(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(HOUR_BOUNDARY_CHECK_WORK)
    }
}
