package com.example.wags.ui.common

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.wags.ui.theme.*

/**
 * Reusable top-app-bar actions block that shows live HR and SpO₂ readings
 * whenever they are available. Drop this into the `actions` slot of any
 * [TopAppBar] to get the same live-sensor display as the Dashboard.
 */
@Composable
fun LiveSensorActions(liveHr: Int?, liveSpO2: Int?) {
    if (liveHr != null || liveSpO2 != null) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(end = 8.dp)
        ) {
            liveHr?.let { bpm ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Favorite,
                        contentDescription = "Heart rate",
                        tint = TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "$bpm",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                }
            }
            liveSpO2?.let { spo2 ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = "SpO₂",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                    Text(
                        text = "$spo2%",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                }
            }
        }
    }
}
