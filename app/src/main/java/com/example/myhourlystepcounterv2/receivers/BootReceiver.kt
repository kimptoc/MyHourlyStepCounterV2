package com.example.myhourlystepcounterv2.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import com.example.myhourlystepcounterv2.data.StepPreferences

import kotlinx.coroutines.CoroutineDispatcher

class BootReceiver(
    private var stepPreferences: StepPreferences? = null,
    private val dispatcher: CoroutineDispatcher? = null
) : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (stepPreferences == null) {
            stepPreferences = StepPreferences(context.applicationContext)
        }
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            // Launch a short coroutine to check the preference and start service if enabled
            CoroutineScope(dispatcher ?: Dispatchers.Default).launch {
                val prefs = stepPreferences ?: StepPreferences(context.applicationContext)
                val enabled = prefs.permanentNotificationEnabled.first()
                if (enabled) {
                    val svcIntent = Intent(context.applicationContext, com.example.myhourlystepcounterv2.services.StepCounterForegroundService::class.java)
                    try {
                        context.startForegroundService(svcIntent)
                    } catch (e: Exception) {
                        // Best-effort: ignore if the system prevents starting a service here
                        android.util.Log.w("BootReceiver", "Failed to start foreground service on boot: ${e.message}")
                    }

                    // Also schedule step reminders if enabled (both first and second)
                    val reminderEnabled = prefs.reminderNotificationEnabled.first()
                    if (reminderEnabled) {
                        com.example.myhourlystepcounterv2.notifications.AlarmScheduler.scheduleStepReminders(
                            context.applicationContext
                        )
                        com.example.myhourlystepcounterv2.notifications.AlarmScheduler.scheduleSecondStepReminder(
                            context.applicationContext
                        )
                        android.util.Log.d("BootReceiver", "Step reminders scheduled on boot (XX:50 and XX:55)")
                    }

                    // Always schedule hour boundary alarms for notification resets
                    com.example.myhourlystepcounterv2.notifications.AlarmScheduler.scheduleHourBoundaryAlarms(
                        context.applicationContext
                    )
                    android.util.Log.d("BootReceiver", "Hour boundary alarms scheduled on boot")

                    // Schedule periodic boundary check alarm (every 15 minutes backup)
                    com.example.myhourlystepcounterv2.notifications.AlarmScheduler.scheduleBoundaryCheckAlarm(
                        context.applicationContext
                    )
                    android.util.Log.d("BootReceiver", "Boundary check alarm scheduled on boot")
                }
            }
        }
    }
}
