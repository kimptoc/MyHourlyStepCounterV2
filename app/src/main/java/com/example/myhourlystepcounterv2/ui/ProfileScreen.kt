package com.example.myhourlystepcounterv2.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.myhourlystepcounterv2.BuildConfig
import com.example.myhourlystepcounterv2.StepTrackerConfig

@Composable
fun ProfileScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Text(
            text = "Profile",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 32.dp)
        )

        // Build Information Section
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Build Information",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            Divider(modifier = Modifier.padding(vertical = 8.dp))

            Text(
                text = "Version: ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(vertical = 8.dp)
            )

            Text(
                text = "Build Time:",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(vertical = 8.dp)
            )

            Text(
                text = BuildConfig.BUILD_TIME,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 8.dp)
            )

            Divider(modifier = Modifier.padding(vertical = 8.dp))
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Configuration Section
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "App Configuration",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            Divider(modifier = Modifier.padding(vertical = 8.dp))

            Text(
                text = "Morning Threshold:",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(vertical = 4.dp)
            )

            Text(
                text = StepTrackerConfig.MORNING_THRESHOLD_DISPLAY,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 4.dp)
            )

            Text(
                text = "(Steps before this hour are marked as unavailable when app reopens mid-day)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(vertical = 8.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Maximum Steps/Hour:",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(vertical = 4.dp)
            )

            Text(
                text = StepTrackerConfig.MAX_STEPS_DISPLAY,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 4.dp)
            )

            Text(
                text = "(Values above this are clamped to prevent sensor anomalies)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(vertical = 8.dp)
            )

            Divider(modifier = Modifier.padding(vertical = 8.dp))
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Permanent notification & wake-lock toggles
        Column(
            horizontalAlignment = Alignment.Start,
            modifier = Modifier.padding(16.dp)
        ) {
            val context = androidx.compose.ui.platform.LocalContext.current
            val preferences = remember { com.example.myhourlystepcounterv2.data.StepPreferences(context.applicationContext) }
            val coroutineScope = rememberCoroutineScope()

            // Permanent notification toggle (defaults to ON)
            val permanentEnabled by preferences.permanentNotificationEnabled.collectAsState(initial = com.example.myhourlystepcounterv2.data.StepPreferences.PERMANENT_NOTIFICATION_DEFAULT)

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 8.dp)
            ) {
                Text(
                    text = "Permanent notification",
                    modifier = Modifier.weight(1f)
                )
                androidx.compose.material3.Switch(
                    checked = permanentEnabled,
                    onCheckedChange = { newVal -> coroutineScope.launch { preferences.savePermanentNotificationEnabled(newVal) } }
                )
            }

            // Wake-lock toggle (defaults to ON)
            val wakeEnabled by preferences.useWakeLock.collectAsState(initial = com.example.myhourlystepcounterv2.data.StepPreferences.USE_WAKE_LOCK_DEFAULT)

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 8.dp)
            ) {
                Text(
                    text = "Keep processor awake (wake-lock)",
                    modifier = Modifier.weight(1f)
                )
                androidx.compose.material3.Switch(
                    checked = wakeEnabled,
                    onCheckedChange = { newVal -> coroutineScope.launch { preferences.saveUseWakeLock(newVal) } }
                )
            }

            // Battery warning for wake-lock
            if (wakeEnabled) {
                Text(
                    text = "⚠️ Wake-lock may significantly impact battery life",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 8.dp)
                )
            }
        }
    }
}
