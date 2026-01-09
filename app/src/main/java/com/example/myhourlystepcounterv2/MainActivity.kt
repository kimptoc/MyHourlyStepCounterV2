package com.example.myhourlystepcounterv2

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myhourlystepcounterv2.data.StepPreferences
import com.example.myhourlystepcounterv2.services.StepCounterForegroundService
import com.example.myhourlystepcounterv2.ui.MyHourlyStepCounterV2App
import com.example.myhourlystepcounterv2.ui.StepCounterViewModel
import com.example.myhourlystepcounterv2.ui.StepCounterViewModelFactory
import com.example.myhourlystepcounterv2.ui.theme.MyHourlyStepCounterV2Theme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var viewModel: StepCounterViewModel

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // Permissions are granted, app will work even without them (just won't get steps)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request permissions if needed
        if (PermissionHelper.getRequiredPermissions().isNotEmpty()) {
            requestPermissionLauncher.launch(PermissionHelper.getRequiredPermissions())
        }

        enableEdgeToEdge()
        setContent {
            MyHourlyStepCounterV2Theme {
                val viewModel: StepCounterViewModel = viewModel(
                    factory = StepCounterViewModelFactory(applicationContext)
                )
                this@MainActivity.viewModel = viewModel
                MyHourlyStepCounterV2App(viewModel)
            }
        }

        // Observe permanent notification preference and start/stop foreground service
        // This runs once in Activity lifecycle, not on every ViewModel initialization
        val preferences = StepPreferences(applicationContext)
        lifecycleScope.launch {
            preferences.permanentNotificationEnabled.collect { enabled ->
                val svcIntent = Intent(applicationContext, StepCounterForegroundService::class.java)
                if (enabled) {
                    // Start foreground service (app is in foreground so this is allowed)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(svcIntent)
                    } else {
                        startService(svcIntent)
                    }
                    android.util.Log.d("MainActivity", "Started foreground service (permanent notification enabled)")
                } else {
                    stopService(svcIntent)
                    android.util.Log.d("MainActivity", "Stopped foreground service (permanent notification disabled)")
                }
            }
        }

        // Schedule step reminder alarms
        lifecycleScope.launch {
            preferences.reminderNotificationEnabled.collect { enabled ->
                if (enabled) {
                    com.example.myhourlystepcounterv2.notifications.AlarmScheduler.scheduleStepReminders(
                        applicationContext
                    )
                    android.util.Log.d("MainActivity", "Step reminders scheduled")
                } else {
                    com.example.myhourlystepcounterv2.notifications.AlarmScheduler.cancelStepReminders(
                        applicationContext
                    )
                    android.util.Log.d("MainActivity", "Step reminders cancelled")
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh step counts when app comes back to foreground (e.g., from Samsung Health)
        // This ensures sensor is re-registered and step data is accurate
        if (this::viewModel.isInitialized) {
            viewModel.refreshStepCounts()
            android.util.Log.d("MainActivity", "onResume: Refreshed step counts after returning from another app")
        }
    }
}
