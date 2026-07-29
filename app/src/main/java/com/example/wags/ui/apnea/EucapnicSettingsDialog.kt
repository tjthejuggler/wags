package com.example.wags.ui.apnea

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.wags.domain.model.EucapnicConfig
import com.example.wags.domain.usecase.breathing.EucapnicScalingEngine
import com.example.wags.ui.theme.TextPrimary

/**
 * Dialog that lets the user configure Eucapnic Diaphragmatic Breathing parameters.
 * 
 * This dialog contains all the Eucapnic configuration sliders and can be used
 * from any active screen (FreeHold, ProgressiveO2, MinBreath, Tables) when the
 * EUCAPNIC_DIAPHRAGMATIC prep type is selected.
 */
@Composable
fun EucapnicSettingsDialog(
    config: EucapnicConfig,
    scalingEngine: EucapnicScalingEngine = EucapnicScalingEngine(),
    onPrepDurationChange: (Int) -> Unit,
    onBpmChange: (Float) -> Unit,
    onInhaleChange: (Float) -> Unit,
    onTopPauseChange: (Float) -> Unit,
    onExhaleChange: (Float) -> Unit,
    onBottomPauseChange: (Float) -> Unit,
    onBreathDepthChange: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Eucapnic Settings", style = MaterialTheme.typography.titleMedium)
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                Text(
                    text = "Slow, controlled diaphragmatic breathing to reduce CO₂ tolerance.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextPrimary
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Use the existing EucapnicConfigSection but without the header
                EucapnicConfigSection(
                    config = config,
                    scalingEngine = scalingEngine,
                    onPrepDurationChange = onPrepDurationChange,
                    onBpmChange = onBpmChange,
                    onInhaleChange = onInhaleChange,
                    onTopPauseChange = onTopPauseChange,
                    onExhaleChange = onExhaleChange,
                    onBottomPauseChange = onBottomPauseChange,
                    onBreathDepthChange = onBreathDepthChange,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done")
            }
        }
    )
}
