package com.example.myhourlystepcounterv2.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import com.example.myhourlystepcounterv2.data.StepPreferences

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            // Launch a short coroutine to check the preference and start service if enabled
            CoroutineScope(Dispatchers.Default).launch {
                val prefs = StepPreferences(context.applicationContext)
                val enabled = prefs.permanentNotificationEnabled.first()
                if (enabled) {
                    val svcIntent = Intent(context.applicationContext, com.example.myhourlystepcounterv2.services.StepCounterForegroundService::class.java)
                    try {
                        context.startForegroundService(svcIntent)
                    } catch (e: Exception) {
                        // Best-effort: ignore if the system prevents starting a service here
                        android.util.Log.w("BootReceiver", "Failed to start foreground service on boot: ${e.message}")
                    }

                    // Also schedule step reminders if enabled
                    val reminderEnabled = prefs.reminderNotificationEnabled.first()
                    if (reminderEnabled) {
                        com.example.myhourlystepcounterv2.notifications.AlarmScheduler.scheduleStepReminders(
                            context.applicationContext
                        )
                        android.util.Log.d("BootReceiver", "Step reminders scheduled on boot")
                    }
                }
            }
        }
    }
}
