package com.example.wags.ui.apnea

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.wags.ui.theme.ButtonPrimary
import com.example.wags.ui.theme.TextPrimary
import com.example.wags.ui.theme.TextSecondary

/**
 * Row of two checkboxes for toggling voice and vibration indications.
 * Shown next to the Start button on timer-driven drill screens
 * (Progressive O₂, O₂/CO₂ tables).
 */
@Composable
fun VoiceVibrationToggles(
    voiceEnabled: Boolean,
    vibrationEnabled: Boolean,
    onVoiceToggle: (Boolean) -> Unit,
    onVibrationToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Voice toggle
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = voiceEnabled,
                onCheckedChange = onVoiceToggle,
                modifier = Modifier.size(24.dp),
                colors = CheckboxDefaults.colors(
                    checkedColor = ButtonPrimary,
                    uncheckedColor = TextSecondary,
                    checkmarkColor = TextPrimary
                )
            )
            Spacer(Modifier.width(4.dp))
            Text(
                "🔊 Voice",
                style = MaterialTheme.typography.bodyMedium,
                color = if (voiceEnabled) TextPrimary else TextSecondary
            )
        }

        Spacer(Modifier.width(24.dp))

        // Vibration toggle
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = vibrationEnabled,
                onCheckedChange = onVibrationToggle,
                modifier = Modifier.size(24.dp),
                colors = CheckboxDefaults.colors(
                    checkedColor = ButtonPrimary,
                    uncheckedColor = TextSecondary,
                    checkmarkColor = TextPrimary
                )
            )
            Spacer(Modifier.width(4.dp))
            Text(
                "📳 Vibration",
                style = MaterialTheme.typography.bodyMedium,
                color = if (vibrationEnabled) TextPrimary else TextSecondary
            )
        }
    }
}
