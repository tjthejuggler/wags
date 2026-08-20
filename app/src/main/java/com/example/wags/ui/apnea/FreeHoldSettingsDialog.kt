package com.example.wags.ui.apnea

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.wags.domain.model.AudioSetting
import com.example.wags.domain.model.Posture
import com.example.wags.domain.model.PrepType
import com.example.wags.domain.model.TimeBuckets
import com.example.wags.domain.model.TimeOfDay
import com.example.wags.ui.theme.SurfaceVariant
import com.example.wags.ui.theme.TextPrimary
import com.example.wags.ui.theme.TextSecondary

/**
 * Dialog that lets the user change the 5 apnea settings (lung volume, prep type,
 * time of day, posture, audio) directly from the FreeHoldActiveScreen before
 * starting a hold. Changes are applied immediately and persisted to SharedPreferences
 * so the main ApneaScreen stays in sync.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FreeHoldSettingsDialog(
    lungVolume: String,
    prepType: String,
    timeOfDay: String,
    posture: String,
    audio: String,
    /** True in By-the-Hour mode — the hour bucket is automatic, so the tod selector is hidden. */
    byHour: Boolean = false,
    /** True when no resonance breathing session ended within the last ~5 minutes (RESONANCE prep locked). */
    resonancePrepLocked: Boolean = false,
    onLungVolumeChange: (String) -> Unit,
    onPrepTypeChange: (String) -> Unit,
    onTimeOfDayChange: (String) -> Unit,
    onPostureChange: (String) -> Unit,
    onAudioChange: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Edit Settings", style = MaterialTheme.typography.titleMedium)
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // ── Lung Volume ──────────────────────────────────────────────
                Text("Lung Volume", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("FULL", "PARTIAL", "EMPTY").forEach { volume ->
                        FilterChip(
                            selected = lungVolume == volume,
                            onClick = { onLungVolumeChange(volume) },
                            label = { Text(volume.displayLungVolumeBanner(), style = MaterialTheme.typography.bodySmall) },
                            modifier = Modifier.height(30.dp),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = SurfaceVariant,
                                selectedLabelColor = TextPrimary
                            )
                        )
                    }
                }

                // ── Prep Type ────────────────────────────────────────────────
                Text("Prep Type", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PrepType.entries.forEach { type ->
                        // RESONANCE prep is staleness-locked: it requires a
                        // resonance breathing session that ended within the last
                        // ~5 minutes. Show 🔒 and swallow taps while locked.
                        val resonanceLocked = type == PrepType.RESONANCE && resonancePrepLocked
                        FilterChip(
                            selected = prepType == type.name,
                            onClick = { if (!resonanceLocked) onPrepTypeChange(type.name) },
                            label = {
                                Text(
                                    if (resonanceLocked) "🔒${type.shortDisplayName()}"
                                    else type.shortDisplayName(),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            },
                            modifier = Modifier.height(30.dp),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = SurfaceVariant,
                                selectedLabelColor = TextPrimary
                            )
                        )
                    }
                }

                // ── Posture ──────────────────────────────────────────────────
                Text("Posture", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Posture.entries.forEach { pos ->
                        FilterChip(
                            selected = posture == pos.name,
                            onClick = { onPostureChange(pos.name) },
                            label = { Text(pos.displayName(), style = MaterialTheme.typography.bodySmall) },
                            modifier = Modifier.height(30.dp),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = SurfaceVariant,
                                selectedLabelColor = TextPrimary
                            )
                        )
                    }
                }

                // ── Audio ────────────────────────────────────────────────────
                Text("Audio", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AudioSetting.entries.forEach { aud ->
                        FilterChip(
                            selected = audio == aud.name,
                            onClick = { onAudioChange(aud.name) },
                            label = { Text(aud.displayName(), style = MaterialTheme.typography.bodySmall) },
                            modifier = Modifier.height(30.dp),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = SurfaceVariant,
                                selectedLabelColor = TextPrimary
                            )
                        )
                    }
                }

                // ── Time of Day / Hour Bucket ────────────────────────────────
                if (byHour) {
                    Text(
                        "Hour Bucket: ${TimeBuckets.display(TimeBuckets.current())} (automatic)",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                } else {
                    Text("Time of Day", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TimeOfDay.entries.forEach { tod ->
                            FilterChip(
                                selected = timeOfDay == tod.name,
                                onClick = { onTimeOfDayChange(tod.name) },
                                label = { Text(tod.displayName(), style = MaterialTheme.typography.bodySmall) },
                                modifier = Modifier.height(30.dp),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = SurfaceVariant,
                                    selectedLabelColor = TextPrimary
                                )
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done")
            }
        }
    )
}
