package com.example.wags.ui.apnea.forecast

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.wags.domain.usecase.apnea.forecast.RecordForecast

/**
 * Summary row shown inside the Free Hold collapsible card.
 * Displays the exact-combo record-breaking probability and an "auto set" button.
 * Tapping the probability opens the full [RecordForecastDialog].
 */
@Composable
fun RecordForecastSummary(
    forecast: RecordForecast?,
    onAutoSet: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val showDialog = remember { mutableStateOf(false) }

    // Dismiss dialog
    if (showDialog.value && forecast != null) {
        RecordForecastDialog(
            forecast = forecast,
            onDismiss = { showDialog.value = false }
        )
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Probability row — tappable to open dialog
        Row(
            modifier = Modifier
                .clickable(enabled = forecast != null) { showDialog.value = true }
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (forecast == null) {
                Text(
                    "Not enough data for forecast",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    "Chance to beat PB: ",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val pctText = if (forecast.exactProbability >= 1.0f) "100%"
                else "${(forecast.exactProbability * 100).toInt()}%"
                Text(
                    pctText,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        // Auto-set button — only shown when forecast is available
        if (forecast != null) {
            Text(
                "auto set",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable { onAutoSet() }
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }
    }
}
