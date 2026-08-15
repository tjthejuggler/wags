package com.example.wags.ui.apnea

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.example.wags.ui.common.grayscale
import com.example.wags.ui.theme.TextDisabled
import com.example.wags.ui.theme.TextPrimary

/**
 * Row of two icon toggles for voice (🔊) and vibration (〰) indications.
 * Shown next to the Start button on timer-driven drill screens
 * (Progressive O₂, O₂/CO₂ tables). The icon itself is the toggle:
 * lit = enabled, greyed = disabled.
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
        IconButton(onClick = { onVoiceToggle(!voiceEnabled) }) {
            Text(
                "🔊",
                style = MaterialTheme.typography.titleMedium,
                modifier = if (!voiceEnabled) Modifier.grayscale() else Modifier,
                color = if (voiceEnabled) TextPrimary else TextDisabled
            )
        }

        // Vibration toggle
        IconButton(onClick = { onVibrationToggle(!vibrationEnabled) }) {
            Text(
                "〰",
                style = MaterialTheme.typography.titleLarge,
                color = if (vibrationEnabled) TextPrimary else TextDisabled
            )
        }
    }
}
