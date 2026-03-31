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
        val pendingResult = goAsync()
        if (stepPreferences == null) {
            stepPreferences = StepPreferences(context.applicationContext)
        }
        val action = intent.action
        if (
            action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            // Launch a short coroutine to check the preference and start service if enabled
            CoroutineScope(dispatcher ?: Dispatchers.Default).launch {
                try {
                    val prefs = stepPreferences ?: StepPreferences(context.applicationContext)
                    val permanentNotificationEnabled = prefs.permanentNotificationEnabled.first()
                    val reminderEnabled = prefs.reminderNotificationEnabled.first()
                    // Always bypass exact alarm permission check in boot/update recovery —
                    // we're already inside the if-block that guarantees a system restart action.
                    if (reminderEnabled) {
                        com.example.myhourlystepcounterv2.notifications.AlarmScheduler.scheduleStepReminders(
                            context.applicationContext,
                            skipPermissionCheck = true
                        )
                        com.example.myhourlystepcounterv2.notifications.AlarmScheduler.scheduleSecondStepReminder(
                            context.applicationContext,
                            skipPermissionCheck = true
                        )
                        android.util.Log.d("BootReceiver", "Step reminders scheduled after restart/update (XX:50 and XX:55)")
                    }

                    com.example.myhourlystepcounterv2.notifications.AlarmScheduler.scheduleHourBoundaryAlarms(
                        context.applicationContext,
                        skipPermissionCheck = true
                    )
                    android.util.Log.d("BootReceiver", "Hour boundary alarms scheduled after restart/update")

                    com.example.myhourlystepcounterv2.notifications.AlarmScheduler.scheduleBoundaryCheckAlarm(
                        context.applicationContext,
                        skipPermissionCheck = true
                    )
                    android.util.Log.d("BootReceiver", "Boundary check alarm scheduled after restart/update")

                    if (permanentNotificationEnabled) {
                        val svcIntent = Intent(context.applicationContext, com.example.myhourlystepcounterv2.services.StepCounterForegroundService::class.java)
                        try {
                            context.startForegroundService(svcIntent)
                        } catch (e: Exception) {
                            // Best-effort: ignore if the system prevents starting a service here
                            android.util.Log.w("BootReceiver", "Failed to start foreground service after restart/update: ${e.message}")
                        }
                    } else {
                        android.util.Log.i("BootReceiver", "Permanent notification disabled; alarms restored without starting service")
                    }
                } finally {
                    pendingResult?.finish()
                }
            }
        } else {
            pendingResult?.finish()
        }
    }
}
