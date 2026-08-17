package com.example.wags.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.wags.domain.usecase.apnea.ApneaVibrationWarningConfig
import com.example.wags.ui.theme.EcgCyan
import com.example.wags.ui.theme.SurfaceVariant
import com.example.wags.ui.theme.TextPrimary
import com.example.wags.ui.theme.TextSecondary

/**
 * Apnea audio/haptics block shown inside the "Apnea" settings card:
 * voice + vibration master toggles, and the customizable vibration
 * warnings for holds and breaths ending (with a "same for both" link).
 */
@Composable
fun ApneaVibrationSettingsSection(
    settings: ApneaVibrationSettings,
    onVoiceEnabledChange: (Boolean) -> Unit,
    onVibrationEnabledChange: (Boolean) -> Unit,
    onBreathSameAsHoldChange: (Boolean) -> Unit,
    onHoldWarningChange: (ApneaVibrationWarningConfig) -> Unit,
    onBreathWarningChange: (ApneaVibrationWarningConfig) -> Unit,
    onTestHoldWarning: () -> Unit,
    onTestBreathWarning: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        HorizontalDivider(color = SurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))

        ToggleRow(
            emoji = "🔊",
            label = "Voice Announcements",
            checked = settings.voiceEnabled,
            onCheckedChange = onVoiceEnabledChange
        )
        ToggleRow(
            emoji = "〰",
            label = "Vibration",
            checked = settings.vibrationEnabled,
            onCheckedChange = onVibrationEnabledChange
        )

        HorizontalDivider(color = SurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))

        Text(
            "Vibration Warnings",
            style = MaterialTheme.typography.titleSmall,
            color = TextSecondary
        )
        Text(
            "Customize the warning vibrations that play while a hold or " +
                "breath phase is ending.",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )

        ToggleRow(
            emoji = "🔗",
            label = "Same vibration for holds & breaths",
            checked = settings.breathSameAsHold,
            onCheckedChange = onBreathSameAsHoldChange
        )

        if (settings.breathSameAsHold) {
            WarningVibrationEditor(
                title = "Hold & Breath Ending",
                config = settings.holdWarning,
                onChange = onHoldWarningChange,
                onTest = onTestHoldWarning,
                masterEnabled = settings.vibrationEnabled
            )
        } else {
            WarningVibrationEditor(
                title = "Hold Ending",
                config = settings.holdWarning,
                onChange = onHoldWarningChange,
                onTest = onTestHoldWarning,
                masterEnabled = settings.vibrationEnabled
            )
            WarningVibrationEditor(
                title = "Breath Ending",
                config = settings.breathWarning,
                onChange = onBreathWarningChange,
                onTest = onTestBreathWarning,
                masterEnabled = settings.vibrationEnabled
            )
        }
    }
}

/** Row with an emoji, label and a trailing switch. */
@Composable
private fun ToggleRow(
    emoji: String,
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(emoji, style = MaterialTheme.typography.titleMedium)
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * Editor for one warning vibration: enable toggle, warning window length,
 * beat intensity, beat rapidness, and the optional final-second pulse
 * (length + intensity). Includes a Test button to preview the pattern.
 */
@Composable
private fun WarningVibrationEditor(
    title: String,
    config: ApneaVibrationWarningConfig,
    onChange: (ApneaVibrationWarningConfig) -> Unit,
    onTest: () -> Unit,
    masterEnabled: Boolean
) {
    val enabled = config.enabled && masterEnabled

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = TextPrimary
            )
            Switch(
                checked = config.enabled,
                onCheckedChange = { onChange(config.copy(enabled = it)) },
                enabled = masterEnabled
            )
        }

        if (config.enabled) {
            LabeledSlider(
                label = "Warning length",
                valueText = "${config.windowSec}s",
                value = config.windowSec.toFloat(),
                onValueChange = { onChange(config.copy(windowSec = it.toInt().coerceIn(1, 20))) },
                valueRange = 1f..20f,
                enabled = masterEnabled
            )
            LabeledSlider(
                label = "Intensity",
                valueText = "${config.intensityPct}%",
                value = config.intensityPct.toFloat(),
                onValueChange = { onChange(config.copy(intensityPct = it.toInt().coerceIn(1, 100))) },
                valueRange = 1f..100f,
                enabled = masterEnabled
            )
            LabeledSlider(
                label = "Beat every",
                valueText = formatSecondsText(config.intervalMs),
                value = config.intervalMs / 1000f,
                onValueChange = {
                    // Snap to quarter-second steps (0.25 s … 2 s)
                    val ms = ((it * 4).toInt() * 250).coerceIn(250, 2000)
                    onChange(config.copy(intervalMs = ms))
                },
                valueRange = 0.25f..2f,
                steps = 6,
                enabled = masterEnabled
            )

            ToggleRow(
                emoji = "❗",
                label = "Final second indicator",
                checked = config.finalPulseEnabled,
                onCheckedChange = { onChange(config.copy(finalPulseEnabled = it)) }
            )

            if (config.finalPulseEnabled) {
                LabeledSlider(
                    label = "Final pulse length",
                    valueText = String.format("%.1fs", config.finalPulseMs / 1000f),
                    value = config.finalPulseMs.toFloat(),
                    onValueChange = {
                        onChange(config.copy(finalPulseMs = ((it / 100f).toInt() * 100).coerceIn(200, 2000)))
                    },
                    valueRange = 200f..2000f,
                    enabled = masterEnabled
                )
                LabeledSlider(
                    label = "Final intensity",
                    valueText = "${config.finalIntensityPct}%",
                    value = config.finalIntensityPct.toFloat(),
                    onValueChange = { onChange(config.copy(finalIntensityPct = it.toInt().coerceIn(1, 100))) },
                    valueRange = 1f..100f,
                    enabled = masterEnabled
                )
            }

            OutlinedButton(
                onClick = onTest,
                enabled = enabled,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
            ) {
                Text("Test Vibration")
            }
        }
    }
}

/** Slider row with a label above and the formatted value to the right. */
@Composable
private fun LabeledSlider(
    label: String,
    valueText: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    enabled: Boolean = true
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Slider(
                value = value,
                onValueChange = onValueChange,
                valueRange = valueRange,
                steps = steps,
                enabled = enabled,
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(
                    thumbColor = EcgCyan,
                    activeTrackColor = EcgCyan,
                    inactiveTrackColor = SurfaceVariant
                )
            )
            Text(
                valueText,
                style = MaterialTheme.typography.bodySmall,
                color = TextPrimary,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}

/** Formats milliseconds as a compact seconds label ("0.25s", "0.5s", "1s", "2s"). */
private fun formatSecondsText(ms: Int): String {
    val rem = ms % 1000
    return if (rem == 0) "${ms / 1000}s"
    else String.format("%.2f", ms / 1000f).trimEnd('0').trimEnd('.') + "s"
}
