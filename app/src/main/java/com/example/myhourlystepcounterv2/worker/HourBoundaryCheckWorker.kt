package com.example.myhourlystepcounterv2.worker

import android.content.Context
import android.content.Intent
import android.app.ForegroundServiceStartNotAllowedException
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.myhourlystepcounterv2.data.StepPreferences
import com.example.myhourlystepcounterv2.services.StepCounterForegroundService
import com.example.myhourlystepcounterv2.worker.WorkManagerScheduler
import com.example.myhourlystepcounterv2.data.StepDatabase
import com.example.myhourlystepcounterv2.data.StepRepository
import kotlinx.coroutines.flow.first

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
            val preferences = StepPreferences(applicationContext)

            // Sweep first and unconditionally. The boundary sweep needs the foreground service
            // to have survived, and app-open needs the user to show up; neither is true in the
            // case that matters most — the service died during the hours it was meant to be
            // recording. This is the path that still reports those hours.
            runCatching { sweepForAnomalies(preferences) }
                .onFailure { android.util.Log.w("HourBoundaryCheckWorker", "Anomaly sweep failed", it) }

            val enabled = preferences.permanentNotificationEnabled.first()
            if (!enabled) {
                android.util.Log.w("HourBoundaryCheckWorker", "Permanent notification disabled; skipping service start")
                return Result.success()
            }

            val svcIntent = Intent(applicationContext, StepCounterForegroundService::class.java)
            try {
                ContextCompat.startForegroundService(applicationContext, svcIntent)
                android.util.Log.i("HourBoundaryCheckWorker", "Started foreground service for boundary check")
            } catch (e: ForegroundServiceStartNotAllowedException) {
                android.util.Log.e("HourBoundaryCheckWorker", "FG service start not allowed", e)
                WorkManagerScheduler.scheduleHourBoundaryCheckOnce(applicationContext)
                return Result.retry()
            } catch (e: IllegalStateException) {
                android.util.Log.e("HourBoundaryCheckWorker", "FG service start failed", e)
                WorkManagerScheduler.scheduleHourBoundaryCheckOnce(applicationContext)
                return Result.retry()
            }

            Result.success()
        } catch (e: Exception) {
            android.util.Log.e("HourBoundaryCheckWorker", "Error checking for missed hour boundaries", e)
            Result.retry()
        }
    }

    private suspend fun sweepForAnomalies(preferences: StepPreferences) {
        val database = StepDatabase.getDatabase(applicationContext)
        StepRepository(
            stepDao = database.stepDao(),
            anomalyDao = database.stepAnomalyDao(),
            snapshotProvider = { preferences.getDeviceTotalSnapshots() },
            sourcePathReader = { preferences.getHourSourcePaths() }
        ).sweepForAnomalies()
    }
}
