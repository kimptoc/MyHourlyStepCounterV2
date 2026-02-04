package com.example.myhourlystepcounterv2.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.app.ForegroundServiceStartNotAllowedException
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import com.example.myhourlystepcounterv2.data.StepPreferences
import com.example.myhourlystepcounterv2.services.StepCounterForegroundService
import com.example.myhourlystepcounterv2.worker.WorkManagerScheduler
import java.util.Calendar

class HourBoundaryReceiver(
    private var stepPreferences: StepPreferences? = null
) : BroadcastReceiver() {
    companion object {
        const val ACTION_HOUR_BOUNDARY = "com.example.myhourlystepcounterv2.ACTION_HOUR_BOUNDARY"
        const val ACTION_BOUNDARY_CHECK = "com.example.myhourlystepcounterv2.ACTION_BOUNDARY_CHECK"
    }

    override fun onReceive(context: Context, intent: Intent) {
        android.util.Log.i("HourBoundary", "onReceive called! action=${intent.action}")

        if (stepPreferences == null) {
            stepPreferences = StepPreferences(context.applicationContext)
        }

        when (intent.action) {
            ACTION_HOUR_BOUNDARY -> {
                android.util.Log.i("HourBoundary", "Hour boundary alarm triggered at ${Calendar.getInstance().time}")
                startServiceForProcessing(context, "hour boundary")
            }
            ACTION_BOUNDARY_CHECK -> {
                android.util.Log.i("HourBoundary", "Boundary check alarm triggered at ${Calendar.getInstance().time}")
                startServiceForProcessing(context, "boundary check")
            }
            else -> {
                android.util.Log.w("HourBoundary", "Unknown action received: ${intent.action}, ignoring")
                return
            }
        }
    }

    private fun startServiceForProcessing(
        context: Context,
        reason: String
    ) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.Default + SupervisorJob()).launch {
            try {
                val preferences = stepPreferences ?: StepPreferences(context.applicationContext)
                val enabled = preferences.permanentNotificationEnabled.first()
                if (!enabled) {
                    android.util.Log.w("HourBoundary", "Permanent notification disabled; skipping service start for $reason")
                    return@launch
                }

                val svcIntent = Intent(context.applicationContext, StepCounterForegroundService::class.java)
                try {
                    ContextCompat.startForegroundService(context.applicationContext, svcIntent)
                    android.util.Log.i("HourBoundary", "Started foreground service for $reason")
                } catch (e: ForegroundServiceStartNotAllowedException) {
                    android.util.Log.e("HourBoundary", "FG service start not allowed for $reason", e)
                    WorkManagerScheduler.scheduleHourBoundaryCheckOnce(context.applicationContext)
                    return@launch
                } catch (e: IllegalStateException) {
                    android.util.Log.e("HourBoundary", "FG service start failed for $reason", e)
                    WorkManagerScheduler.scheduleHourBoundaryCheckOnce(context.applicationContext)
                    return@launch
                }

                if (reason == "boundary check") {
                    AlarmScheduler.scheduleBoundaryCheckAlarm(context.applicationContext)
                } else {
                    AlarmScheduler.scheduleHourBoundaryAlarms(context.applicationContext)
                }
            } catch (e: Exception) {
                android.util.Log.e("HourBoundary", "Error starting foreground service for $reason", e)
            } finally {
                pendingResult?.finish()
            }
        }
    }
}
