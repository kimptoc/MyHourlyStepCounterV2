package com.example.myhourlystepcounterv2.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
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
import kotlinx.coroutines.delay

enum class AppDestinations(
    val label: String,
    val icon: ImageVector,
) {
    HOME("Home", Icons.Default.Home),
    HISTORY("History", Icons.Default.Home), // Placeholder - will use different icon
    PROFILE("Profile", Icons.Default.AccountBox),
}

@PreviewScreenSizes
@Composable
fun MyHourlyStepCounterV2App(viewModel: StepCounterViewModel) {
    var currentDestination by rememberSaveable { mutableStateOf(AppDestinations.HOME) }
    val context = LocalContext.current

    // Initialize ViewModel
    LaunchedEffect(Unit) {
        viewModel.initialize(context)
    }

    // Schedule hour boundary checks at exact hour transitions
    LaunchedEffect(Unit) {
        viewModel.scheduleHourBoundaryCheck()
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
        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
            when (currentDestination) {
                AppDestinations.HOME -> HomeScreen(
                    viewModel = viewModel,
                    modifier = Modifier.padding(innerPadding)
                )
                AppDestinations.HISTORY -> HistoryScreen(
                    viewModel = viewModel,
                    modifier = Modifier.padding(innerPadding)
                )
                AppDestinations.PROFILE -> ProfileScreen(
                    modifier = Modifier.padding(innerPadding)
                )
            }
        }
    }
}
