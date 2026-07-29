package com.example.wags.ui.apnea

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.wags.domain.model.EucapnicConfig
import com.example.wags.ui.theme.SurfaceVariant
import com.example.wags.ui.theme.TextPrimary

/**
 * Button that displays the current Eucapnic settings and opens the settings dialog when clicked.
 * 
 * Shows a summary of the current configuration (prep duration, BPM, breath depth) to give
 * users a quick overview without cluttering the UI with all sliders.
 */
@Composable
fun EucapnicSettingsButton(
    config: EucapnicConfig,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, SurfaceVariant)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = "Eucapnic Settings",
                tint = TextPrimary
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Eucapnic: ${config.prepDurationSec}s prep, ${config.breathsPerMin} BPM, ${config.breathDepthPercent}% depth",
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary
            )
        }
    }
}
