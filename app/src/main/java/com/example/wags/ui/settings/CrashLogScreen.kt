package com.example.wags.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.wags.data.crash.CrashLogWriter
import com.example.wags.ui.theme.*
import java.io.File

/**
 * Screen that lists all saved crash logs and lets the user view or clear them.
 * Accessible from Settings → Crash Logs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CrashLogScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    var logs by remember { mutableStateOf(CrashLogWriter.listLogs(context)) }
    var selectedLog by remember { mutableStateOf<File?>(null) }
    var showClearDialog by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = { Text("Crash Logs", style = MaterialTheme.typography.headlineMedium) },
                navigationIcon = {
                    IconButton(onClick = {
                        if (selectedLog != null) selectedLog = null
                        else onNavigateBack()
                    }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    if (logs.isNotEmpty() && selectedLog == null) {
                        IconButton(onClick = { showClearDialog = true }) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Clear all logs",
                                tint = ButtonDanger
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark)
            )
        }
    ) { padding ->
        if (selectedLog != null) {
            // ── Detail view: show full crash log content ──────────────────
            CrashLogDetail(
                file = selectedLog!!,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            )
        } else if (logs.isEmpty()) {
            // ── Empty state ──────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No crash logs recorded ✓",
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextSecondary
                )
            }
        } else {
            // ── List of crash logs ───────────────────────────────────────
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Text(
                        text = "${logs.size} crash log${if (logs.size != 1) "s" else ""} saved",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    Spacer(Modifier.height(8.dp))
                }
                items(logs, key = { it.name }) { file ->
                    CrashLogListItem(
                        file = file,
                        onClick = { selectedLog = file },
                        onDelete = {
                            CrashLogWriter.delete(file)
                            logs = CrashLogWriter.listLogs(context)
                        }
                    )
                }
            }
        }
    }

    // ── Clear-all confirmation dialog ────────────────────────────────────────
    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            containerColor = SurfaceDark,
            title = { Text("Clear All Crash Logs?") },
            text = { Text("This will permanently delete all ${logs.size} crash log files.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        CrashLogWriter.clearAll(context)
                        logs = CrashLogWriter.listLogs(context)
                        showClearDialog = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = ButtonDanger)
                ) {
                    Text("Delete All")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun CrashLogListItem(
    file: File,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    // Extract a readable timestamp from the filename: crash_2026-04-01_14-30-22.txt
    val displayName = file.name
        .removePrefix("crash_")
        .removeSuffix(".txt")
        .replace('_', ' ')
        .replace('-', ':')
        // Fix the date part: first 10 chars should use dashes not colons
        .let { raw ->
            if (raw.length >= 10) {
                val datePart = raw.substring(0, 10).replace(':', '-')
                datePart + raw.substring(10)
            } else raw
        }

    // Read first meaningful line (after header) for a preview
    val preview = remember(file.name) {
        try {
            file.readLines()
                .firstOrNull { it.contains("Exception") || it.contains("Error") }
                ?.trim()
                ?: "Crash log"
        } catch (_: Exception) {
            "Crash log"
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary
                )
                Text(
                    text = preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = ReadinessRed,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = TextSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun CrashLogDetail(file: File, modifier: Modifier = Modifier) {
    val content = remember(file.name) {
        try {
            file.readText()
        } catch (e: Exception) {
            "Error reading crash log: ${e.message}"
        }
    }

    SelectionContainer {
        Text(
            text = content,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 15.sp
            ),
            color = TextPrimary,
            modifier = modifier
                .verticalScroll(rememberScrollState())
                .padding(12.dp)
        )
    }
}
