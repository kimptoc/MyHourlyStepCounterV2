package com.example.myhourlystepcounterv2.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myhourlystepcounterv2.StepTrackerConfig
import com.example.myhourlystepcounterv2.ui.theme.GradientGreenEnd
import com.example.myhourlystepcounterv2.ui.theme.GradientGreenStart
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(
    viewModel: StepCounterViewModel,
    modifier: Modifier = Modifier
) {
    val hourlySteps by viewModel.hourlySteps.collectAsState()
    val dailySteps by viewModel.dailySteps.collectAsState()
    val currentTime by viewModel.currentTime.collectAsState()

    // Format current time with minutes and seconds
    val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    val dateFormatter = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault())

    val formattedTime = timeFormatter.format(Date(currentTime))
    val formattedDate = dateFormatter.format(Date(currentTime))

    // Calculate progress for hourly goal (250 steps from StepTrackerConfig)
    val hourlyGoal = StepTrackerConfig.STEP_REMINDER_THRESHOLD
    val progress = (hourlySteps.toFloat() / hourlyGoal).coerceIn(0f, 1f)

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Date and Time Display with Icons
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 8.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.DateRange,
                contentDescription = "Calendar",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = formattedDate,
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 32.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Schedule,
                contentDescription = "Time",
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = formattedTime,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center
            )
        }

        // Elevated Card for Hourly Steps with Gradient Background
        ElevatedCard(
            elevation = CardDefaults.elevatedCardElevation(
                defaultElevation = 8.dp
            ),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.padding(bottom = 32.dp)
        ) {
            Box(
                modifier = Modifier
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                GradientGreenStart,
                                GradientGreenEnd
                            )
                        )
                    )
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Icon
                    Icon(
                        imageVector = Icons.Filled.Star,
                        contentDescription = "Steps",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .size(48.dp)
                            .padding(bottom = 16.dp)
                    )

                    Text(
                        text = "Steps This Hour",
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    // Circular Progress Ring with Step Count
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(200.dp)
                    ) {
                        // Background circle
                        CircularProgressIndicator(
                            progress = { 1f },
                            modifier = Modifier.size(200.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            strokeWidth = 12.dp,
                        )
                        // Progress circle
                        CircularProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.size(200.dp),
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 12.dp,
                        )
                        // Step count in center
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = hourlySteps.toString(),
                                style = MaterialTheme.typography.displayLarge.copy(
                                    fontSize = 64.sp
                                ),
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "${(progress * 100).toInt()}%",
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Text(
                        text = "Goal: $hourlyGoal steps",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 16.dp)
                    )
                }
            }
        }

        // Card for Daily Total Steps
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Row(
                modifier = Modifier.padding(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.DirectionsWalk,
                    contentDescription = "Daily Total",
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        text = "Total Steps Today",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = dailySteps.toString(),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }
    }
}
