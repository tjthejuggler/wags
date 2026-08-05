package com.example.wags.ui.apnea

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.wags.data.db.entity.EucapnicPastConfigurationEntity
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
    onDismiss: () -> Unit,
    // Past configurations support
    pastConfigurations: List<EucapnicPastConfigurationEntity> = emptyList(),
    onPastConfigurationsClick: (() -> Unit)? = null
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
                    .padding(vertical = 4.dp)
            ) {
                Spacer(modifier = Modifier.height(4.dp))
                
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
                    modifier = Modifier.fillMaxWidth(),
                    showHeader = false
                )
            }
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
            ) {
                // Past Configurations button (optional)
                if (onPastConfigurationsClick != null) {
                    OutlinedButton(
                        onClick = onPastConfigurationsClick,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Past Configurations")
                    }
                }
                
                // Done button
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Done")
                }
            }
        }
    )
}
