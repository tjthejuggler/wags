package com.example.wags.ui.apnea

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.wags.ui.theme.ButtonPrimary
import com.example.wags.ui.theme.SurfaceDark
import com.example.wags.ui.theme.TextPrimary
import com.example.wags.ui.theme.TextSecondary

/**
 * Popup dialog for filtering Min Breath session history by the 5 standard apnea settings.
 * Each category is multi-select; the small All/None toggle next to its header selects
 * or clears the whole category.
 */
@Composable
fun MinBreathFilterDialog(
    filterLungVolume: Set<String>,
    filterPrepType: Set<String>,
    filterTimeOfDay: Set<String>,
    filterPosture: Set<String>,
    filterAudio: Set<String>,
    /** True in By-the-Hour mode — filters offer the 24 hour buckets instead of Morning/Day/Night. */
    byHour: Boolean = false,
    /** Current session settings — the "Current" target of each category's header toggle. */
    currentLungVolume: String,
    currentPrepType: String,
    currentTimeOfDay: String,
    currentPosture: String,
    currentAudio: String,
    onLungVolumeChange: (Set<String>) -> Unit,
    onPrepTypeChange: (Set<String>) -> Unit,
    onTimeOfDayChange: (Set<String>) -> Unit,
    onPostureChange: (Set<String>) -> Unit,
    onAudioChange: (Set<String>) -> Unit,
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

            MultiSelectFilterCategory(
                label = "Lung Volume",
                options = SettingFilterOptions.LUNG_VOLUMES,
                optionLabel = SettingFilterOptions::lungVolumeLabel,
                selected = filterLungVolume,
                currentValue = currentLungVolume,
                onSelectionChange = onLungVolumeChange
            )

            MultiSelectFilterCategory(
                label = "Prep",
                options = SettingFilterOptions.PREP_TYPES,
                optionLabel = SettingFilterOptions::prepTypeShortLabel,
                selected = filterPrepType,
                currentValue = currentPrepType,
                onSelectionChange = onPrepTypeChange
            )

            MultiSelectFilterCategory(
                label = "Posture",
                options = SettingFilterOptions.POSTURES,
                optionLabel = SettingFilterOptions::postureLabel,
                selected = filterPosture,
                currentValue = currentPosture,
                onSelectionChange = onPostureChange
            )

            MultiSelectFilterCategory(
                label = "Audio",
                options = SettingFilterOptions.AUDIOS,
                optionLabel = SettingFilterOptions::audioLabel,
                selected = filterAudio,
                currentValue = currentAudio,
                onSelectionChange = onAudioChange
            )

            MultiSelectFilterCategory(
                label = if (byHour) "Hour" else "Time of Day",
                options = SettingFilterOptions.timeOfDayOptions(byHour),
                optionLabel = SettingFilterOptions::timeBucketLabel,
                selected = filterTimeOfDay,
                currentValue = currentTimeOfDay,
                onSelectionChange = onTimeOfDayChange
            )

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

/** Build a short label describing the current filter combination. */
fun buildMinBreathFilterSummary(state: MinBreathUiState, byHour: Boolean = false): String {
    val parts = listOfNotNull(
        settingFilterSummaryPart(state.filterLungVolume, SettingFilterOptions.LUNG_VOLUMES, SettingFilterOptions::lungVolumeLabel),
        settingFilterSummaryPart(state.filterPrepType, SettingFilterOptions.PREP_TYPES, SettingFilterOptions::prepTypeLabel),
        settingFilterSummaryPart(state.filterTimeOfDay, SettingFilterOptions.timeOfDayOptions(byHour), SettingFilterOptions::timeBucketLabel),
        settingFilterSummaryPart(state.filterPosture, SettingFilterOptions.POSTURES, SettingFilterOptions::postureLabel),
        settingFilterSummaryPart(state.filterAudio, SettingFilterOptions.AUDIOS, SettingFilterOptions::audioLabel)
    )
    return if (parts.isEmpty()) "All Sessions" else parts.joinToString(" · ")
}
