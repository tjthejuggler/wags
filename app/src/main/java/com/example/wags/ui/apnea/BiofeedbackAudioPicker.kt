package com.example.wags.ui.apnea

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.wags.domain.usecase.session.BiofeedbackHrSound
import com.example.wags.domain.usecase.session.BiofeedbackSpo2Texture
import com.example.wags.ui.common.grayscale
import com.example.wags.ui.theme.*

// ── Picker Button ────────────────────────────────────────────────────────────

/**
 * Button shown on the apnea setup screen when audio = BIOFEEDBACK.
 * Styled like [GuidedAudioPickerButton] / [SongPickerButton].
 */
@Composable
fun BiofeedbackPickerButton(onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = SurfaceDark,
        tonalElevation = 2.dp
    ) {
        Button(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = SurfaceVariant,
                contentColor = TextPrimary
            )
        ) {
            Text(
                "Choose Biofeedback Sound",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.grayscale()
            )
        }
    }
}

// ── Selected Config Banner ───────────────────────────────────────────────────

/**
 * Banner showing the currently selected biofeedback combination.
 * Tapping it re-opens the picker, so it replaces [BiofeedbackPickerButton]
 * while a selection exists (same pattern as [SelectedGuidedAudioBanner]).
 */
@Composable
fun SelectedBiofeedbackBanner(
    hrSound: BiofeedbackHrSound,
    spo2Texture: BiofeedbackSpo2Texture,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = SurfaceVariant,
        tonalElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "Biofeedback: ${hrSound.displayName} · ${spo2Texture.displayName}",
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ── Picker Dialog ────────────────────────────────────────────────────────────

/**
 * Dialog for choosing the biofeedback sonification setup:
 *  - the instrument struck per heartbeat (live HR), and
 *  - the background texture whose quality follows live SpO2.
 *
 * Deliberately structured as independent labelled sections so richer
 * sonification modes (e.g. adaptive meditation music) can be added as new
 * sections later without reshaping the dialog.
 */
@Composable
fun BiofeedbackPickerDialog(
    selectedHrSound: BiofeedbackHrSound?,
    selectedSpo2Texture: BiofeedbackSpo2Texture?,
    onSelectHrSound: (BiofeedbackHrSound) -> Unit,
    onSelectSpo2Texture: (BiofeedbackSpo2Texture) -> Unit,
    onDismiss: () -> Unit,
    onPreviewHrSound: (BiofeedbackHrSound) -> Unit = {},
    onPreviewSpo2Texture: (BiofeedbackSpo2Texture) -> Unit = {},
    onStopPreview: () -> Unit = {},
    hrVolume: Float = 1f,
    spo2Volume: Float = 1f,
    onHrVolumeChange: (Float) -> Unit = {},
    onSpo2VolumeChange: (Float) -> Unit = {}
) {
    // 0 = HR volume dialog, 1 = SpO2 volume dialog, null = none (set by
    // long-pressing any card in the corresponding section).
    var volumeDialogFor by remember { mutableStateOf<Int?>(null) }
    AlertDialog(
        onDismissRequest = { onStopPreview(); onDismiss() },
        containerColor = BackgroundDark,
        title = {
            Text(
                "Biofeedback Sound",
                style = MaterialTheme.typography.titleLarge,
                color = TextPrimary
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                Text(
                    "Sound generated live from your metrics while you hold.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "The instrument and texture you choose are saved with each hold's history entry.",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextDisabled
                )
                Spacer(Modifier.height(12.dp))

                Text(
                    "Heartbeat sound (pace = live HR)",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary
                )
                Text(
                    "Long-press a card to set its volume",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextDisabled
                )
                Spacer(Modifier.height(6.dp))
                BiofeedbackHrSound.entries.forEach { sound ->
                    BiofeedbackOptionCard(
                        title = sound.displayName,
                        subtitle = sound.description,
                        isSelected = selectedHrSound == sound,
                        onClick = {
                            onSelectHrSound(sound)
                            onPreviewHrSound(sound)
                        },
                        onLongClick = { volumeDialogFor = 0 }
                    )
                    Spacer(Modifier.height(6.dp))
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    "Background soundscape (scenes = live SpO2)",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary
                )
                Text(
                    "Long-press a card to set its volume",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextDisabled
                )
                Spacer(Modifier.height(6.dp))
                BiofeedbackSpo2Texture.entries.forEach { tex ->
                    BiofeedbackOptionCard(
                        title = tex.displayName,
                        subtitle = tex.description,
                        isSelected = selectedSpo2Texture == tex,
                        onClick = {
                            onSelectSpo2Texture(tex)
                            onPreviewSpo2Texture(tex)
                        },
                        onLongClick = { volumeDialogFor = 1 }
                    )
                    Spacer(Modifier.height(6.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onStopPreview(); onDismiss() }) {
                Text(
                    if (selectedHrSound != null) "Done" else "Cancel",
                    color = TextSecondary
                )
            }
        }
    )

    val dialogFor = volumeDialogFor
    if (dialogFor != null) {
        val isHr = dialogFor == 0
        BiofeedbackVolumeDialog(
            title = if (isHr) "Heartbeat volume" else "Soundscape volume",
            volume = if (isHr) hrVolume else spo2Volume,
            onVolumeChange = if (isHr) onHrVolumeChange else onSpo2VolumeChange,
            onDismiss = { volumeDialogFor = null }
        )
    }
}

/** Small slider dialog for one biofeedback layer's volume. */
@Composable
private fun BiofeedbackVolumeDialog(
    title: String,
    volume: Float,
    onVolumeChange: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BackgroundDark,
        title = {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                color = TextPrimary
            )
        },
        text = {
            Column {
                Text(
                    "${(volume * 100).toInt()}%",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
                Slider(
                    value = volume,
                    onValueChange = onVolumeChange,
                    modifier = Modifier.fillMaxWidth(),
                    colors = SliderDefaults.colors(
                        thumbColor = TextSecondary,
                        activeTrackColor = TextSecondary
                    )
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done", color = TextPrimary)
            }
        }
    )
}

// ── Option card (inside dialog) ──────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BiofeedbackOptionCard(
    title: String,
    subtitle: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {}
) {
    val borderColor = if (isSelected) TextSecondary else Color.Transparent
    val bgColor = if (isSelected) TextSecondary.copy(alpha = 0.08f) else SurfaceDark

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .border(
                width = if (isSelected) 1.5.dp else 0.dp,
                color = borderColor,
                shape = MaterialTheme.shapes.medium
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        colors = CardDefaults.cardColors(containerColor = bgColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextPrimary,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
            if (isSelected) {
                Text("✓", color = TextPrimary, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

// ── Missing live-data confirmation (shown at hold start) ─────────────────────

/**
 * Confirmation shown when starting a BIOFEEDBACK hold while both the HR
 * instrument and the SpO2 soundscape are configured, but one of the live
 * feeds is missing. Confirming sets the missing side to its "None" option
 * and starts the hold; cancelling keeps the configuration untouched.
 */
@Composable
fun BiofeedbackMissingDataDialog(
    missingHr: Boolean,
    missingSpo2: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val missing = buildList {
        if (missingHr) add("heart rate")
        if (missingSpo2) add("SpO2")
    }.joinToString(" and ")
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BackgroundDark,
        title = {
            Text(
                "Missing live data",
                style = MaterialTheme.typography.titleLarge,
                color = TextPrimary
            )
        },
        text = {
            Column {
                Text(
                    "Your biofeedback setup uses both HR and SpO2, but no live " +
                        "$missing data is coming from the sensor right now.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Continue anyway? The $missing sound will be set to \"None\" " +
                        "for this and future holds (you can change it back in the picker).",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Continue", color = TextPrimary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary)
            }
        }
    )
}
