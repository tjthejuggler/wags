package com.example.wags.ui.apnea

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.wags.domain.model.AudioSetting
import com.example.wags.domain.model.Posture
import com.example.wags.domain.model.PrepType
import com.example.wags.domain.model.TimeOfDay
import com.example.wags.ui.theme.ButtonPrimary
import com.example.wags.ui.theme.SurfaceDark
import com.example.wags.ui.theme.SurfaceVariant
import com.example.wags.ui.theme.TextPrimary
import com.example.wags.ui.theme.TextSecondary

/**
 * Popup dialog for filtering Progressive O₂ session history by the 5 standard apnea settings.
 * Empty string = "All" for that dimension.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ProgressiveO2FilterDialog(
    filterLungVolume: String,
    filterPrepType: String,
    filterTimeOfDay: String,
    filterPosture: String,
    filterAudio: String,
    onLungVolumeChange: (String) -> Unit,
    onPrepTypeChange: (String) -> Unit,
    onTimeOfDayChange: (String) -> Unit,
    onPostureChange: (String) -> Unit,
    onAudioChange: (String) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .background(SurfaceDark, RoundedCornerShape(12.dp))
                .border(1.dp, TextSecondary.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Filter Sessions",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary
            )

            // Lung Volume
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Lung Volume", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(selected = filterLungVolume.isEmpty(), onClick = { onLungVolumeChange("") }, label = { Text("All", style = MaterialTheme.typography.labelSmall) }, modifier = Modifier.height(30.dp), colors = chipColors)
                    listOf("FULL", "PARTIAL", "EMPTY").forEach { lv ->
                        FilterChip(
                            selected = filterLungVolume == lv,
                            onClick = { onLungVolumeChange(lv) },
                            label = { Text(if (lv == "PARTIAL") "Half" else lv.lowercase().replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.height(30.dp),
                            colors = chipColors
                        )
                    }
                }
            }

            // Prep Type
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Prep", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(selected = filterPrepType.isEmpty(), onClick = { onPrepTypeChange("") }, label = { Text("All", style = MaterialTheme.typography.labelSmall) }, modifier = Modifier.height(30.dp), colors = chipColors)
                    PrepType.entries.forEach { pt ->
                        FilterChip(selected = filterPrepType == pt.name, onClick = { onPrepTypeChange(pt.name) }, label = { Text(pt.displayName(), style = MaterialTheme.typography.labelSmall) }, modifier = Modifier.height(30.dp), colors = chipColors)
                    }
                }
            }

            // Time of Day
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Time of Day", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(selected = filterTimeOfDay.isEmpty(), onClick = { onTimeOfDayChange("") }, label = { Text("All", style = MaterialTheme.typography.labelSmall) }, modifier = Modifier.height(30.dp), colors = chipColors)
                    TimeOfDay.entries.forEach { tod ->
                        FilterChip(selected = filterTimeOfDay == tod.name, onClick = { onTimeOfDayChange(tod.name) }, label = { Text(tod.displayName(), style = MaterialTheme.typography.labelSmall) }, modifier = Modifier.height(30.dp), colors = chipColors)
                    }
                }
            }

            // Posture
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Posture", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(selected = filterPosture.isEmpty(), onClick = { onPostureChange("") }, label = { Text("All", style = MaterialTheme.typography.labelSmall) }, modifier = Modifier.height(30.dp), colors = chipColors)
                    Posture.entries.forEach { pos ->
                        FilterChip(selected = filterPosture == pos.name, onClick = { onPostureChange(pos.name) }, label = { Text(pos.displayName(), style = MaterialTheme.typography.labelSmall) }, modifier = Modifier.height(30.dp), colors = chipColors)
                    }
                }
            }

            // Audio
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Audio", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(selected = filterAudio.isEmpty(), onClick = { onAudioChange("") }, label = { Text("All", style = MaterialTheme.typography.labelSmall) }, modifier = Modifier.height(30.dp), colors = chipColors)
                    AudioSetting.entries.forEach { aud ->
                        FilterChip(selected = filterAudio == aud.name, onClick = { onAudioChange(aud.name) }, label = { Text(aud.displayName(), style = MaterialTheme.typography.labelSmall) }, modifier = Modifier.height(30.dp), colors = chipColors)
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            // Reset + Done buttons
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onReset,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
                ) { Text("Reset to Current") }
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = ButtonPrimary, contentColor = TextPrimary)
                ) { Text("Done") }
            }
        }
    }
}

private val chipColors
    @Composable get() = FilterChipDefaults.filterChipColors(
        selectedContainerColor = SurfaceVariant,
        selectedLabelColor = TextPrimary,
        labelColor = TextSecondary
    )

/** Build a short label describing the current filter combination. */
fun buildProgressiveO2FilterSummary(state: ProgressiveO2UiState): String {
    val parts = mutableListOf<String>()
    if (state.filterLungVolume.isNotEmpty()) parts.add(
        when (state.filterLungVolume) {
            "PARTIAL" -> "Half"
            else -> state.filterLungVolume.lowercase().replaceFirstChar { it.uppercase() }
        }
    )
    if (state.filterPrepType.isNotEmpty()) parts.add(
        runCatching { PrepType.valueOf(state.filterPrepType).displayName() }
            .getOrDefault(state.filterPrepType)
    )
    if (state.filterTimeOfDay.isNotEmpty()) parts.add(
        runCatching { TimeOfDay.valueOf(state.filterTimeOfDay).displayName() }
            .getOrDefault(state.filterTimeOfDay)
    )
    if (state.filterPosture.isNotEmpty()) parts.add(
        runCatching { Posture.valueOf(state.filterPosture).displayName() }
            .getOrDefault(state.filterPosture)
    )
    if (state.filterAudio.isNotEmpty()) parts.add(
        runCatching { AudioSetting.valueOf(state.filterAudio).displayName() }
            .getOrDefault(state.filterAudio)
    )
    return if (parts.isEmpty()) "All Sessions" else parts.joinToString(" · ")
}
