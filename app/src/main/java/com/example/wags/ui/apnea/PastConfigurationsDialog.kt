package com.example.wags.ui.apnea

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.wags.data.db.entity.EucapnicPastConfigurationEntity
import com.example.wags.ui.theme.*
import java.util.Locale

/**
 * Modal dialog listing all saved eucapnic configurations, most recently used first.
 *
 * Each card shows the configuration name, BPM, inhale/exhale times, and total
 * prep duration. Tapping a card restores that configuration. A button at the
 * bottom lets the user save the current configuration under a new name.
 *
 * @param configurations Saved configurations ordered by lastUsedAtMs descending
 * @param onConfigurationSelected Callback when a saved configuration is tapped
 * @param onSaveCurrentClick Callback when "Save Current Configuration" is tapped
 * @param onDismiss Callback when the dialog is dismissed
 */
@Composable
fun PastConfigurationsDialog(
    configurations: List<EucapnicPastConfigurationEntity>,
    onConfigurationSelected: (EucapnicPastConfigurationEntity) -> Unit,
    onSaveCurrentClick: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.75f),
            shape = MaterialTheme.shapes.large,
            color = SurfaceDark,
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // ── Header ─────────────────────────────────────────────────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Past Configurations",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    TextButton(onClick = onDismiss) {
                        Text("Close", color = TextSecondary)
                    }
                }

                // ── Configuration list ──────────────────────────────────────
                if (configurations.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "No saved configurations yet",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(
                            items = configurations,
                            key = { it.configId }
                        ) { entity ->
                            ConfigurationCard(
                                entity = entity,
                                onClick = { onConfigurationSelected(entity) }
                            )
                        }
                    }
                }

                // ── Save current button ─────────────────────────────────────
                Button(
                    onClick = onSaveCurrentClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SurfaceVariant,
                        contentColor = TextPrimary
                    )
                ) {
                    Text("Save Current Configuration")
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Private sub-composables
// ─────────────────────────────────────────────────────────────────────────────

/**
 * A single card representing a saved eucapnic configuration.
 */
@Composable
private fun ConfigurationCard(
    entity: EucapnicPastConfigurationEntity,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = SurfaceVariant
        ),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // Name
            Text(
                text = entity.name,
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary,
                fontWeight = FontWeight.Medium
            )

            Spacer(modifier = Modifier.height(4.dp))

            // Details row: BPM · inhale/exhale · duration
            val details = buildString {
                append(formatBpm(entity.breathsPerMin))
                append(" · ")
                append(formatSecondsDecimal(entity.inhaleSec))
                append(" / ")
                append(formatSecondsDecimal(entity.exhaleSec))
                append(" · ")
                append(formatDuration(entity.prepDurationSec))
            }
            Text(
                text = details,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )

            // Usage stats (if used before)
            if (entity.useCount > 0) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Used ${entity.useCount}×",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextDisabled
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Formatting helpers
// ─────────────────────────────────────────────────────────────────────────────

private fun formatBpm(value: Float): String =
    String.format(Locale.US, "%.1f BPM", value)

private fun formatSecondsDecimal(value: Float): String =
    String.format(Locale.US, "%.1fs", value)

private fun formatDuration(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return if (s == 0) "${m}min" else "${m}m ${s}s"
}
