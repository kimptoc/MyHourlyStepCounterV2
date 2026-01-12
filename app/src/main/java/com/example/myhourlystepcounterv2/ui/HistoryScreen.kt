package com.example.myhourlystepcounterv2.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.myhourlystepcounterv2.data.StepEntity
import com.example.myhourlystepcounterv2.ui.theme.ActivityHigh
import com.example.myhourlystepcounterv2.ui.theme.ActivityLow
import com.example.myhourlystepcounterv2.ui.theme.ActivityMedium
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(
    viewModel: StepCounterViewModel,
    modifier: Modifier = Modifier
) {
    val dayHistory by viewModel.dayHistory.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            text = "Today's Activity",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        if (dayHistory.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Star,
                    contentDescription = "No Activity",
                    modifier = Modifier
                        .size(64.dp)
                        .padding(bottom = 16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "No activity recorded yet",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Start walking to see your hourly progress!",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            // Calculate summary statistics
            val totalSteps = dayHistory.sumOf { it.stepCount }
            val averageSteps = if (dayHistory.isNotEmpty()) totalSteps / dayHistory.size else 0
            val peakHour = dayHistory.maxByOrNull { it.stepCount }

            // Summary Statistics Cards
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SummaryStatCard(
                    title = "Total",
                    value = totalSteps.toString(),
                    icon = Icons.AutoMirrored.Filled.DirectionsWalk,
                    modifier = Modifier.weight(1f)
                )
                SummaryStatCard(
                    title = "Average",
                    value = averageSteps.toString(),
                    icon = Icons.Filled.Check,
                    modifier = Modifier.weight(1f)
                )
                SummaryStatCard(
                    title = "Peak",
                    value = peakHour?.stepCount.toString(),
                    icon = Icons.Filled.Star,
                    modifier = Modifier.weight(1f)
                )
            }

            // Activity Bar Chart for All Hours
            if (dayHistory.isNotEmpty()) {
                ActivityBarChart(
                    history = dayHistory,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
            }
        }
    }
}

@Composable
fun SummaryStatCard(
    title: String,
    value: String,
    icon: ImageVector,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun ActivityBarChart(
    history: List<StepEntity>,
    modifier: Modifier = Modifier
) {
    val maxSteps = history.maxOfOrNull { it.stepCount } ?: 1

    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Hourly Activity",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            history.forEach { step ->
                val hourFormatter = SimpleDateFormat("h a", Locale.getDefault())
                val progress = if (maxSteps > 0) step.stepCount.toFloat() / maxSteps else 0f
                val activityLevel = getActivityLevel(step.stepCount)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = hourFormatter.format(Date(step.timestamp)),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.width(48.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .weight(1f)
                            .height(20.dp),
                        color = activityLevel.color,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = step.stepCount.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.width(40.dp),
                        textAlign = TextAlign.End,
                        fontWeight = FontWeight.Bold,
                        color = activityLevel.color
                    )
                }
            }
        }
    }
}

@Composable
fun StepHistoryCard(step: StepEntity) {
    val hourFormatter = SimpleDateFormat("h:mm a", Locale.getDefault())
    val formattedHour = hourFormatter.format(Date(step.timestamp))
    val activityLevel = getActivityLevel(step.stepCount)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = activityLevel.color.copy(alpha = 0.15f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = activityLevel.icon,
                    contentDescription = activityLevel.label,
                    tint = activityLevel.color,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = formattedHour,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = activityLevel.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = activityLevel.color,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Text(
                text = step.stepCount.toString(),
                style = MaterialTheme.typography.headlineMedium,
                color = activityLevel.color,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.End
            )
        }
    }
}

data class ActivityLevel(
    val label: String,
    val color: Color,
    val icon: ImageVector
)

fun getActivityLevel(steps: Int): ActivityLevel {
    return when {
        steps >= 1000 -> ActivityLevel("High Activity", ActivityHigh, Icons.Filled.Star)
        steps >= 250 -> ActivityLevel("Medium Activity", ActivityMedium, Icons.Filled.Check)
        else -> ActivityLevel("Low Activity", ActivityLow, Icons.Filled.Home)
    }
}
