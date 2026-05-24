package com.example.wags.ui.apnea

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.wags.ui.theme.*

// ─────────────────────────────────────────────────────────────────────────────
// Guided Hyperventilation Section (shared between Free Hold & Min Breath)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Checkbox + edit icon for guided hyperventilation.
 * Shown above the start button when prep type is HYPER.
 * The edit icon opens a bottom sheet to configure the phase durations.
 */
@Composable
fun GuidedHyperSection(
    enabled: Boolean,
    relaxedExhaleSec: Int,
    purgeExhaleSec: Int,
    transitionSec: Int,
    showStartMp3WithHyper: Boolean = false,
    startMp3WithHyper: Boolean = false,
    onEnabledChange: (Boolean) -> Unit,
    onStartMp3WithHyperChange: (Boolean) -> Unit = {},
    onEditClick: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .clickable { onEnabledChange(!enabled) }
            ) {
                Checkbox(
                    checked = enabled,
                    onCheckedChange = onEnabledChange,
                    modifier = Modifier.size(32.dp),
                    colors = CheckboxDefaults.colors(
                        checkedColor = TextPrimary,
                        uncheckedColor = TextSecondary,
                        checkmarkColor = BackgroundDark
                    )
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Guided Hyperventilation",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary
                )
            }
            // Edit icon — always visible so user can configure even when disabled
            IconButton(onClick = onEditClick) {
                Icon(
                    imageVector = Icons.Outlined.Edit,
                    contentDescription = "Edit guided hyperventilation settings",
                    tint = TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        // Summary of configured durations when enabled
        if (enabled) {
            val parts = buildList {
                if (relaxedExhaleSec > 0) add("Relaxed ${relaxedExhaleSec}s")
                if (purgeExhaleSec > 0) add("Purge ${purgeExhaleSec}s")
                if (transitionSec > 0) add("Transition ${transitionSec}s")
            }
            if (parts.isNotEmpty()) {
                Text(
                    text = parts.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    modifier = Modifier.padding(start = 36.dp, bottom = 2.dp)
                )
            }

            // "Start MP3 with Hyper" checkbox — only shown when audio is GUIDED
            if (showStartMp3WithHyper) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .padding(start = 32.dp)
                        .clickable { onStartMp3WithHyperChange(!startMp3WithHyper) }
                ) {
                    Checkbox(
                        checked = startMp3WithHyper,
                        onCheckedChange = onStartMp3WithHyperChange,
                        modifier = Modifier.size(28.dp),
                        colors = CheckboxDefaults.colors(
                            checkedColor = TextPrimary,
                            uncheckedColor = TextSecondary,
                            checkmarkColor = BackgroundDark
                        )
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Start MP3 with Hyper",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }
        }
    }
}

/**
 * Bottom sheet for editing the three guided hyperventilation phase durations.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuidedHyperEditSheet(
    relaxedExhaleSec: Int,
    purgeExhaleSec: Int,
    transitionSec: Int,
    onRelaxedExhaleChange: (Int) -> Unit,
    onPurgeExhaleChange: (Int) -> Unit,
    onTransitionChange: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
        tonalElevation = 4.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Text(
                "Guided Hyperventilation",
                style = MaterialTheme.typography.titleLarge,
                color = TextPrimary
            )
            Text(
                "Set the duration (in seconds) for each phase. Set to 0 to skip a phase.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )

            GuidedHyperPhaseRow(
                label = "Relaxed Exhale",
                value = relaxedExhaleSec,
                onValueChange = onRelaxedExhaleChange
            )
            GuidedHyperPhaseRow(
                label = "Purge Exhale",
                value = purgeExhaleSec,
                onValueChange = onPurgeExhaleChange
            )
            GuidedHyperPhaseRow(
                label = "Transition",
                value = transitionSec,
                onValueChange = onTransitionChange
            )

            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SurfaceVariant,
                    contentColor = TextPrimary
                )
            ) {
                Text("Done")
            }
        }
    }
}

/**
 * A single row in the edit sheet: label on the left, numeric input on the right.
 */
@Composable
fun GuidedHyperPhaseRow(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = TextPrimary
        )
        Box(
            modifier = Modifier
                .width(72.dp)
                .height(40.dp)
                .border(1.dp, SurfaceVariant, RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            BasicTextField(
                value = if (value == 0) "" else value.toString(),
                onValueChange = { text ->
                    val parsed = text.filter { it.isDigit() }.take(4).toIntOrNull() ?: 0
                    onValueChange(parsed)
                },
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    textAlign = TextAlign.Center,
                    color = TextPrimary
                ),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                cursorBrush = SolidColor(TextPrimary)
            )
        }
    }
}
