package com.example.wags.ui.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.wags.data.debug.QueuedNote
import com.example.wags.data.debug.ScreenContextMapper.ScreenContext
import com.example.wags.domain.model.NoteType
import com.example.wags.ui.theme.*

/**
 * Dialog shown when the user taps the debug bubble.
 *
 * Two tabs:
 * - **Note** — compose a note with Save (draft) and Queue buttons
 * - **Queue** — view queued notes with Submit All and per-note delete
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugNoteDialog(
    screenContext: ScreenContext,
    currentRoute: String,
    draftText: String,
    draftType: NoteType,
    queuedNotes: List<QueuedNote>,
    noteCountOnScreen: Int,
    onDismiss: () -> Unit,
    onSaveDraft: (NoteType, String) -> Unit,
    onQueueNote: (NoteType, String) -> Unit,
    onSubmitQueue: () -> Unit,
    onRemoveFromQueue: (String) -> Unit
) {
    var noteText by remember(draftText) { mutableStateOf(draftText) }
    var selectedType by remember(draftType) { mutableStateOf(draftType) }
    var showQueue by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
        shape = RoundedCornerShape(16.dp),
        title = {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "🐛 Debug Note",
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary
                    )
                    if (queuedNotes.isNotEmpty()) {
                        BadgedBox(
                            badge = {
                                Badge(
                                    containerColor = ReadinessRed,
                                    contentColor = Color.White
                                ) {
                                    Text(queuedNotes.size.toString(), fontSize = 10.sp)
                                }
                            }
                        ) {
                            TextButton(onClick = { showQueue = !showQueue }) {
                                Text(
                                    if (showQueue) "✏️ Note" else "📋 Queue",
                                    color = EcgCyan,
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    "Screen: ${screenContext.label}",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                Text(
                    "Source: ${screenContext.sourceFile}",
                    style = MaterialTheme.typography.bodySmall,
                    color = EcgCyan
                )
                Text(
                    "Functions: ${screenContext.sourceFunctions}",
                    style = MaterialTheme.typography.bodySmall,
                    color = EcgCyan
                )
                if (noteCountOnScreen > 0) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        "$noteCountOnScreen note(s) on this screen",
                        style = MaterialTheme.typography.labelSmall,
                        color = ReadinessOrange
                    )
                }
            }
        },
        text = {
            if (showQueue && queuedNotes.isNotEmpty()) {
                // ── Queue view ──────────────────────────────────────────────
                LazyColumn(
                    modifier = Modifier.heightIn(max = 300.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(queuedNotes, key = { it.id }) { note ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = SurfaceVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            note.noteType.label,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = when (note.noteType) {
                                                NoteType.BUG -> ReadinessRed
                                                NoteType.FEATURE -> ReadinessGreen
                                                NoteType.NOTE -> EcgCyan
                                            },
                                            fontWeight = FontWeight.Bold
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            note.screenLabel,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = TextSecondary
                                        )
                                    }
                                    Text(
                                        note.noteText,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextPrimary,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        note.sourceFile,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = EcgCyan.copy(alpha = 0.7f)
                                    )
                                }
                                IconButton(
                                    onClick = { onRemoveFromQueue(note.id) },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Text("✕", color = TextSecondary, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            } else {
                // ── Note compose view ───────────────────────────────────────
                showQueue = false

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Note type selector
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        NoteType.entries.forEach { type ->
                            FilterChip(
                                selected = selectedType == type,
                                onClick = { selectedType = type },
                                label = { Text(type.label) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = when (type) {
                                        NoteType.BUG -> ReadinessRed.copy(alpha = 0.3f)
                                        NoteType.FEATURE -> ReadinessGreen.copy(alpha = 0.3f)
                                        NoteType.NOTE -> EcgCyan.copy(alpha = 0.3f)
                                    },
                                    selectedLabelColor = TextPrimary
                                )
                            )
                        }
                    }

                    // Note text input
                    OutlinedTextField(
                        value = noteText,
                        onValueChange = { noteText = it },
                        label = { Text("Describe the ${selectedType.label.lowercase()}…") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = EcgCyan,
                            unfocusedBorderColor = SurfaceVariant,
                            cursorColor = EcgCyan,
                            focusedLabelColor = EcgCyan,
                            unfocusedLabelColor = TextSecondary
                        ),
                        shape = RoundedCornerShape(8.dp)
                    )
                }
            }
        },
        confirmButton = {
            if (showQueue && queuedNotes.isNotEmpty()) {
                // Queue view buttons
                Button(
                    onClick = {
                        onSubmitQueue()
                        showQueue = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ReadinessGreen,
                        contentColor = BackgroundDark
                    )
                ) {
                    Text("Submit All (${queuedNotes.size})")
                }
            } else {
                // Note compose buttons
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Save draft
                    OutlinedButton(
                        onClick = { onSaveDraft(selectedType, noteText) },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
                    ) {
                        Text("Save")
                    }
                    // Queue
                    Button(
                        onClick = {
                            if (noteText.isNotBlank()) {
                                onQueueNote(selectedType, noteText)
                                noteText = ""
                                selectedType = NoteType.BUG
                            }
                        },
                        enabled = noteText.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ReadinessOrange,
                            contentColor = BackgroundDark,
                            disabledContainerColor = SurfaceVariant,
                            disabledContentColor = TextSecondary
                        )
                    ) {
                        Text("Queue")
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = TextSecondary)
            }
        }
    )
}
