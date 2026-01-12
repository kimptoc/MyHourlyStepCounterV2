package com.example.myhourlystepcounterv2.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.PreviewScreenSizes

enum class AppDestinations(
    val label: String,
    val icon: ImageVector,
) {
    HISTORY("History", Icons.AutoMirrored.Filled.List),
    HOME("Home", Icons.Filled.Home),
    PROFILE("Profile", Icons.Filled.AccountBox),
}

@PreviewScreenSizes
@Composable
fun MyHourlyStepCounterV2App(viewModel: StepCounterViewModel) {
    var currentDestination by rememberSaveable { mutableStateOf(AppDestinations.HOME) }
    val context = LocalContext.current

    // Initialize ViewModel with application context (not Activity context)
    // to avoid context leaks in long-lived objects (DB, preferences, sensor manager, WorkManager)
    // NOTE: initialize() also calls scheduleHourBoundaryCheck() internally to ensure proper ordering
    LaunchedEffect(Unit) {
        viewModel.initialize(context.applicationContext)
    }

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            AppDestinations.entries.forEach {
                item(
                    icon = {
                        Icon(
                            it.icon,
                            contentDescription = it.label
                        )
                    },
                    label = { Text(it.label) },
                    selected = it == currentDestination,
                    onClick = { currentDestination = it }
                )
            }
        }
    ) {
        when (currentDestination) {
            AppDestinations.HOME -> HomeScreen(viewModel = viewModel)
            AppDestinations.HISTORY -> HistoryScreen(viewModel = viewModel)
            AppDestinations.PROFILE -> ProfileScreen()
        }
    }
}
