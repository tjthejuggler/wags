package com.example.wags.ui.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.example.wags.data.debug.SavedNote
import com.example.wags.data.debug.ScreenContextMapper.ScreenContext
import com.example.wags.domain.model.NoteType
import com.example.wags.ui.theme.*

private enum class DebugTab { NOTE, QUEUE, SAVED }

/**
 * Dialog shown when the user taps the debug bubble.
 *
 * Three tabs:
 * - **Note** — compose a note with Save and Queue buttons
 * - **Queue** — view queued notes (all screens) with Submit All and per-note delete
 * - **Saved** — view all saved notes, click to view/edit, queue, or delete
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugNoteDialog(
    screenContext: ScreenContext,
    currentRoute: String,
    queuedNotes: List<QueuedNote>,
    savedNotes: List<SavedNote>,
    noteCountOnScreen: Int,
    onDismiss: () -> Unit,
    onSaveNote: (NoteType, String) -> Unit,
    onQueueNote: (NoteType, String) -> Unit,
    onSubmitQueue: () -> Unit,
    onRemoveFromQueue: (String) -> Unit,
    onUpdateSavedNote: (String, NoteType, String) -> Unit,
    onDeleteSavedNote: (String) -> Unit,
    onQueueSavedNote: (String) -> Unit
) {
    var noteText by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf(NoteType.BUG) }
    var activeTab by remember { mutableStateOf(DebugTab.NOTE) }
    var editingNote by remember { mutableStateOf<SavedNote?>(null) }

    val totalSaved = savedNotes.size

    // If an editing note is set, show the edit dialog instead
    if (editingNote != null) {
        SavedNoteEditDialog(
            note = editingNote!!,
            onUpdate = { noteId, noteType, text ->
                onUpdateSavedNote(noteId, noteType, text)
                editingNote = null
            },
            onQueue = { noteId ->
                onQueueSavedNote(noteId)
                editingNote = null
            },
            onDelete = { noteId ->
                onDeleteSavedNote(noteId)
                editingNote = null
            },
            onDismiss = { editingNote = null }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
        shape = RoundedCornerShape(16.dp),
        title = {
            Column {
                // ── Tab row ──────────────────────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    DebugTabButton(
                        label = "✏️ Note",
                        selected = activeTab == DebugTab.NOTE,
                        onClick = { activeTab = DebugTab.NOTE }
                    )
                    DebugTabButton(
                        label = "📋 Queue",
                        badge = queuedNotes.size.takeIf { it > 0 },
                        badgeColor = ReadinessOrange,
                        selected = activeTab == DebugTab.QUEUE,
                        onClick = { activeTab = DebugTab.QUEUE }
                    )
                    DebugTabButton(
                        label = "💾 Saved",
                        badge = totalSaved.takeIf { it > 0 },
                        badgeColor = ReadinessGreen,
                        selected = activeTab == DebugTab.SAVED,
                        onClick = { activeTab = DebugTab.SAVED }
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                // ── Screen context (shown on Note tab only) ───────────────────
                if (activeTab == DebugTab.NOTE) {
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
                    if (noteCountOnScreen > 0) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            "$noteCountOnScreen note(s) queued on this screen",
                            style = MaterialTheme.typography.labelSmall,
                            color = ReadinessOrange
                        )
                    }
                }
            }
        },
        text = {
            when (activeTab) {
                DebugTab.NOTE -> NoteComposeContent(
                    noteText = noteText,
                    selectedType = selectedType,
                    onNoteTextChange = { noteText = it },
                    onTypeChange = { selectedType = it }
                )

                DebugTab.QUEUE -> QueueContent(
                    queuedNotes = queuedNotes,
                    onRemoveFromQueue = onRemoveFromQueue
                )

                DebugTab.SAVED -> SavedContent(
                    savedNotes = savedNotes,
                    onClickNote = { editingNote = it }
                )
            }
        },
        confirmButton = {
            when (activeTab) {
                DebugTab.NOTE -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            if (noteText.isNotBlank()) {
                                onSaveNote(selectedType, noteText)
                                noteText = ""
                                selectedType = NoteType.BUG
                            }
                        },
                        enabled = noteText.isNotBlank(),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = TextPrimary,
                            disabledContentColor = TextSecondary
                        )
                    ) {
                        Text("Save")
                    }
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

                DebugTab.QUEUE -> if (queuedNotes.isNotEmpty()) {
                    Button(
                        onClick = {
                            onSubmitQueue()
                            activeTab = DebugTab.NOTE
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ReadinessGreen,
                            contentColor = BackgroundDark
                        )
                    ) {
                        Text("Submit All (${queuedNotes.size})")
                    }
                }

                DebugTab.SAVED -> { /* no action button needed */ }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = TextSecondary)
            }
        }
    )
}

// ── Private sub-composables ───────────────────────────────────────────────────

@Composable
private fun DebugTabButton(
    label: String,
    selected: Boolean,
    badge: Int? = null,
    badgeColor: Color = ReadinessOrange,
    onClick: () -> Unit
) {
    BadgedBox(
        badge = {
            if (badge != null && badge > 0) {
                Badge(containerColor = badgeColor, contentColor = BackgroundDark) {
                    Text(badge.toString(), fontSize = 9.sp)
                }
            }
        }
    ) {
        TextButton(
            onClick = onClick,
            colors = ButtonDefaults.textButtonColors(
                contentColor = if (selected) EcgCyan else TextSecondary
            )
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
            )
        }
    }
}

@Composable
private fun NoteComposeContent(
    noteText: String,
    selectedType: NoteType,
    onNoteTextChange: (String) -> Unit,
    onTypeChange: (NoteType) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            NoteType.entries.forEach { type ->
                FilterChip(
                    selected = selectedType == type,
                    onClick = { onTypeChange(type) },
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
        OutlinedTextField(
            value = noteText,
            onValueChange = onNoteTextChange,
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

@Composable
private fun QueueContent(
    queuedNotes: List<QueuedNote>,
    onRemoveFromQueue: (String) -> Unit
) {
    if (queuedNotes.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("No notes queued", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
        }
    } else {
        LazyColumn(
            modifier = Modifier.heightIn(max = 300.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(queuedNotes, key = { it.id }) { note ->
                NoteCard(
                    typeLabel = note.noteType.label,
                    typeColor = noteTypeColor(note.noteType),
                    screenLabel = note.screenLabel,
                    noteText = note.noteText,
                    sourceFile = note.sourceFile,
                    trailingContent = {
                        IconButton(
                            onClick = { onRemoveFromQueue(note.id) },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Text("✕", color = TextSecondary, fontSize = 12.sp)
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun SavedContent(
    savedNotes: List<SavedNote>,
    onClickNote: (SavedNote) -> Unit
) {
    if (savedNotes.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "No saved notes",
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall
            )
        }
    } else {
        LazyColumn(
            modifier = Modifier.heightIn(max = 320.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(savedNotes, key = { it.id }) { note ->
                NoteCard(
                    typeLabel = note.noteType.label,
                    typeColor = noteTypeColor(note.noteType),
                    screenLabel = note.screenLabel,
                    noteText = note.noteText,
                    sourceFile = note.sourceFile,
                    trailingContent = null,
                    modifier = Modifier.clickable { onClickNote(note) }
                )
            }
        }
    }
}

/**
 * Full-screen edit dialog for a saved note.
 * Allows viewing the full text, editing, queuing, or deleting.
 */
@Composable
private fun SavedNoteEditDialog(
    note: SavedNote,
    onUpdate: (String, NoteType, String) -> Unit,
    onQueue: (String) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var editType by remember(note.id) { mutableStateOf(note.noteType) }
    var editText by remember(note.id) { mutableStateOf(note.noteText) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
        shape = RoundedCornerShape(16.dp),
        title = {
            Column {
                Text(
                    "📝 Saved Note",
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "📍 ${note.screenLabel}  •  ${note.timestamp}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
                Text(
                    note.sourceFile,
                    style = MaterialTheme.typography.labelSmall,
                    color = EcgCyan.copy(alpha = 0.7f)
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Type selector
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    NoteType.entries.forEach { type ->
                        FilterChip(
                            selected = editType == type,
                            onClick = { editType = type },
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
                // Editable text
                OutlinedTextField(
                    value = editText,
                    onValueChange = { editText = it },
                    label = { Text("Note text") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 200.dp),
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
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Delete button
                OutlinedButton(
                    onClick = { onDelete(note.id) },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = ReadinessRed)
                ) {
                    Text("Delete")
                }
                // Queue button
                Button(
                    onClick = { onQueue(note.id) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ReadinessOrange,
                        contentColor = BackgroundDark
                    )
                ) {
                    Text("Queue")
                }
                // Save (update) button
                Button(
                    onClick = { onUpdate(note.id, editType, editText) },
                    enabled = editText.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ReadinessGreen,
                        contentColor = BackgroundDark,
                        disabledContainerColor = SurfaceVariant,
                        disabledContentColor = TextSecondary
                    )
                ) {
                    Text("Update")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary)
            }
        }
    )
}

@Composable
private fun NoteCard(
    typeLabel: String,
    typeColor: Color,
    screenLabel: String,
    noteText: String,
    sourceFile: String,
    trailingContent: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceVariant),
        modifier = modifier.fillMaxWidth()
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
                        typeLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = typeColor,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        screenLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }
                Text(
                    noteText,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    sourceFile,
                    style = MaterialTheme.typography.labelSmall,
                    color = EcgCyan.copy(alpha = 0.7f)
                )
            }
            trailingContent?.invoke()
        }
    }
}

private fun noteTypeColor(type: NoteType): Color = when (type) {
    NoteType.BUG -> ReadinessRed
    NoteType.FEATURE -> ReadinessGreen
    NoteType.NOTE -> EcgCyan
}
