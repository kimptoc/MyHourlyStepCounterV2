package com.example.myhourlystepcounterv2

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myhourlystepcounterv2.ui.MyHourlyStepCounterV2App
import com.example.myhourlystepcounterv2.ui.StepCounterViewModel
import com.example.myhourlystepcounterv2.ui.StepCounterViewModelFactory
import com.example.myhourlystepcounterv2.ui.theme.MyHourlyStepCounterV2Theme

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
