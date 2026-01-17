package com.example.myhourlystepcounterv2.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.myhourlystepcounterv2.data.StepDatabase
import java.util.Calendar

/**
 * Daily cleanup worker that removes old step records from the database.
 * Runs once per day at 3:00 AM to maintain database size.
 *
 * This worker no longer handles hour boundaries - that's now exclusively managed
 * by HourBoundaryReceiver to avoid race conditions and triple-write conflicts.
 */
class StepCounterWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return try {
            val database = StepDatabase.getDatabase(applicationContext)

            // Calculate cutoff timestamp: keep records from last 30 days only
            val cutoffTimestamp = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, -30)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis

            // Delete records older than 30 days
            database.stepDao().deleteOldSteps(cutoffTimestamp)

            android.util.Log.i(
                "DailyCleanup",
                "Daily cleanup complete: Deleted records older than ${java.util.Date(cutoffTimestamp)}"
            )

            Result.success()
        } catch (e: Exception) {
            android.util.Log.e("DailyCleanup", "Error in daily cleanup", e)
            Result.retry()
        }
    }
}
