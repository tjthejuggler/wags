package com.example.wags.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.wags.ui.common.LiveSensorActionsCallback
import com.example.wags.ui.common.LiveSensorActionsCallback
import com.example.wags.ui.theme.*

private val defaultAcronymLines = listOf(
    "We're Always Getting Something",
    "With Animals Get Started"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onNavigateBack: () -> Unit, onNavigateToSettings: () -> Unit = {}) {
    var acronymLines by remember { mutableStateOf(defaultAcronymLines) }
    var showEditDialog by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = { Text("About", style = MaterialTheme.typography.headlineMedium) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    LiveSensorActionsCallback(onNavigateToSettings)
                    IconButton(onClick = { showEditDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Edit acronym lines",
                            tint = TextSecondary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = acronymLines.joinToString("\n"),
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary,
                textAlign = TextAlign.Center
            )
        }
    }

    if (showEditDialog) {
        AcronymEditDialog(
            lines = acronymLines,
            onDismiss = { showEditDialog = false },
            onSave = { updated ->
                acronymLines = updated
                showEditDialog = false
            }
        )
    }
}

@Composable
private fun AcronymEditDialog(
    lines: List<String>,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit
) {
    var editableLines by remember { mutableStateOf(lines.toMutableList()) }
    var newLineText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
        title = {
            Text(
                text = "WAGS Acronym Lines",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 300.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    itemsIndexed(editableLines) { index, line ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = line,
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextPrimary,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = {
                                    editableLines = editableLines.toMutableList().also { it.removeAt(index) }
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Remove line",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        HorizontalDivider(color = SurfaceVariant)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = newLineText,
                        onValueChange = { newLineText = it },
                        placeholder = {
                            Text(
                                "Add new line…",
                                color = TextSecondary,
                                style = MaterialTheme.typography.bodySmall
                            )
                        },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedBorderColor = EcgCyan,
                            unfocusedBorderColor = SurfaceVariant
                        )
                    )
                    IconButton(
                        onClick = {
                            val trimmed = newLineText.trim()
                            if (trimmed.isNotEmpty()) {
                                editableLines = editableLines.toMutableList().also { it.add(trimmed) }
                                newLineText = ""
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Add line",
                            tint = EcgCyan
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(editableLines.toList()) }) {
                Text("Save", color = EcgCyan)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary)
            }
        }
    )
}
