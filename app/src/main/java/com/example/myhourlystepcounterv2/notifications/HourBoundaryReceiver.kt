package com.example.myhourlystepcounterv2.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import com.example.myhourlystepcounterv2.data.StepPreferences
import com.example.myhourlystepcounterv2.sensor.StepSensorManager
import java.util.Calendar

class HourBoundaryReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_HOUR_BOUNDARY = "com.example.myhourlystepcounterv2.ACTION_HOUR_BOUNDARY"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_HOUR_BOUNDARY) return

        android.util.Log.i("HourBoundary", "Hour boundary alarm triggered at ${Calendar.getInstance().time}")

        // Use goAsync() to extend BroadcastReceiver lifecycle for coroutine work
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.Default + SupervisorJob()).launch {
            try {
                val sensorManager = StepSensorManager.getInstance(context.applicationContext)
                val preferences = StepPreferences(context.applicationContext)

                // Get current device total from sensor (or fallback to preferences)
                val currentDeviceTotal = sensorManager.getCurrentTotalSteps()
                val fallbackTotal = preferences.totalStepsDevice.first()

                val deviceTotal = if (currentDeviceTotal > 0) {
                    currentDeviceTotal
                } else {
                    android.util.Log.w(
                        "HourBoundary",
                        "Sensor returned 0, using preferences fallback: $fallbackTotal"
                    )
                    fallbackTotal
                }

                // Calculate current hour timestamp
                val currentHourTimestamp = Calendar.getInstance().apply {
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis

                android.util.Log.i(
                    "HourBoundary",
                    "Processing hour boundary: deviceTotal=$deviceTotal, newHourTimestamp=$currentHourTimestamp"
                )

                // Reset sensor for new hour (updates display to 0)
                sensorManager.resetForNewHour(deviceTotal)

                // Update preferences with new hour baseline
                preferences.saveHourData(
                    hourStartStepCount = deviceTotal,
                    currentTimestamp = currentHourTimestamp,
                    totalSteps = deviceTotal
                )

                // Reset reminder/achievement flags for new hour
                preferences.saveReminderSentThisHour(false)
                preferences.saveAchievementSentThisHour(false)

                android.util.Log.i(
                    "HourBoundary",
                    "✓ Hour boundary processed: Sensor reset to baseline=$deviceTotal, display=0, notification will update"
                )
            } catch (e: Exception) {
                android.util.Log.e("HourBoundary", "Error processing hour boundary", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
