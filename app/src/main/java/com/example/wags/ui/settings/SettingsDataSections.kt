package com.example.wags.ui.settings

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.wags.data.crash.CrashLogWriter
import com.example.wags.ui.theme.ButtonDanger
import com.example.wags.ui.theme.EcgCyan
import com.example.wags.ui.theme.ReadinessGreen
import com.example.wags.ui.theme.ReadinessRed
import com.example.wags.ui.theme.SurfaceVariant
import com.example.wags.ui.theme.TextPrimary
import com.example.wags.ui.theme.TextSecondary

// ── Data export / import sub-section ──────────────────────────────────────────

@Composable
fun DataExportImportSection(
    isExporting: Boolean,
    isImporting: Boolean,
    message: String?,
    error: String?,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onDismissMessage: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SettingsSubSectionLabel("Backup & Restore")
        Text(
            "Export all your data (readings, sessions, records, settings, device history) " +
                "to a backup file. Import a backup to restore everything.",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Export Data", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Save all data to a ZIP file",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
            if (isExporting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = EcgCyan,
                    strokeWidth = 2.dp
                )
            } else {
                Button(
                    onClick = onExport,
                    enabled = !isImporting,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SurfaceVariant,
                        contentColor = TextPrimary
                    )
                ) {
                    Text("Export")
                }
            }
        }

        SettingsSubSectionDivider()

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Import Data", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Restore from a backup file",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
            if (isImporting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = EcgCyan,
                    strokeWidth = 2.dp
                )
            } else {
                OutlinedButton(
                    onClick = onImport,
                    enabled = !isExporting,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
                ) {
                    Text("Import")
                }
            }
        }

        if (message != null) {
            SettingsSubSectionDivider()
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = ReadinessGreen.copy(alpha = 0.15f)
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                ) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = ReadinessGreen
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        onClick = onDismissMessage,
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("Dismiss", color = ReadinessGreen)
                    }
                }
            }
        }

        if (error != null) {
            SettingsSubSectionDivider()
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = ReadinessRed.copy(alpha = 0.15f)
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                ) {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = ReadinessRed
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        onClick = onDismissMessage,
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("Dismiss", color = ReadinessRed)
                    }
                }
            }
        }
    }
}

// ── Crash logs sub-section ────────────────────────────────────────────────────

@Composable
fun CrashLogsSection(onViewLogs: () -> Unit) {
    val context = LocalContext.current
    val logCount = remember { CrashLogWriter.listLogs(context).size }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            SettingsSubSectionLabel("Crash Logs")
            Text(
                if (logCount == 0) "No crashes recorded"
                else "$logCount crash log${if (logCount != 1) "s" else ""} saved",
                style = MaterialTheme.typography.bodySmall,
                color = if (logCount > 0) ReadinessRed else TextSecondary
            )
        }
        OutlinedButton(
            onClick = onViewLogs,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
        ) {
            Text("View")
        }
    }
}

// ── Debug mode sub-section ────────────────────────────────────────────────────

@Composable
fun DebugModeSection(
    debugModeEnabled: Boolean,
    debugFileDirUri: String,
    onToggleDebugMode: (Boolean) -> Unit,
    onChooseDirectory: () -> Unit,
    onClearDirectory: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SettingsSubSectionLabel("Debug Bubble")
        Text(
            "Show a floating bubble on every screen. Tap it to log bugs, features, or notes " +
                "that are saved with the current screen's source file info to debug_wags.json.",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )

        // Toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Enable Debug Bubble", style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (debugModeEnabled) "Bubble is visible" else "Bubble is hidden",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (debugModeEnabled) ReadinessGreen else TextSecondary
                )
            }
            Switch(
                checked = debugModeEnabled,
                onCheckedChange = onToggleDebugMode,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = EcgCyan,
                    checkedThumbColor = TextPrimary
                )
            )
        }

        // File directory (only shown when debug mode is on)
        if (debugModeEnabled) {
            SettingsSubSectionDivider()

            Text("Output File Location", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Choose the folder where debug_wags.json will be written.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    if (debugFileDirUri.isNotBlank()) {
                        val displayPath = try {
                            Uri.parse(debugFileDirUri).lastPathSegment ?: debugFileDirUri
                        } catch (_: Exception) { debugFileDirUri }
                        Text(
                            displayPath,
                            style = MaterialTheme.typography.bodySmall,
                            color = ReadinessGreen
                        )
                    } else {
                        Text(
                            "Using app internal storage",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (debugFileDirUri.isNotBlank()) {
                        IconButton(onClick = onClearDirectory, modifier = Modifier.size(32.dp)) {
                            Icon(
                                Icons.Default.Clear,
                                contentDescription = "Clear directory",
                                tint = TextSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    OutlinedButton(
                        onClick = onChooseDirectory,
                        modifier = Modifier.height(32.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
                    ) {
                        Text(
                            if (debugFileDirUri.isNotBlank()) "Change" else "Choose Folder",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}

// ── Advice sub-section ────────────────────────────────────────────────────────

@Composable
fun AdviceSettingsSection(
    adviceBySection: Map<String, List<com.example.wags.data.db.entity.AdviceEntity>>,
    onOpenSection: (String) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SettingsSubSectionLabel("Advice")
        Text(
            "Add personal reminders or tips that appear at the top of each section's screen.",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )

        com.example.wags.ui.common.AdviceSection.all.forEach { section ->
            val count = adviceBySection[section]?.size ?: 0
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        com.example.wags.ui.common.AdviceSection.label(section),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        if (count == 0) "No advice set"
                        else "$count piece${if (count != 1) "s" else ""} of advice",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (count > 0) ReadinessGreen else TextSecondary
                    )
                }
                OutlinedButton(
                    onClick = { onOpenSection(section) },
                    modifier = Modifier.height(32.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
                ) {
                    Text(
                        if (count > 0) "Manage" else "Add",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}
