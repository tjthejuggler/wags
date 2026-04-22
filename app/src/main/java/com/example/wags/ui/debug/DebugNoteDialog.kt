package com.example.wags.ui.debug

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.wags.data.debug.ScreenContextMapper.ScreenContext
import com.example.wags.domain.model.NoteType
import com.example.wags.ui.theme.*

/**
 * Dialog shown when the user taps the debug bubble.
 * Lets the user describe a bug, feature request, or note
 * and submit it to [debug_wags.json].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugNoteDialog(
    screenContext: ScreenContext,
    noteCountOnScreen: Int,
    onDismiss: () -> Unit,
    onSubmit: (NoteType, String) -> Unit
) {
    var noteText by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf(NoteType.BUG) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
        shape = RoundedCornerShape(16.dp),
        title = {
            Column {
                Text(
                    "Debug Note",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
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
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "$noteCountOnScreen note(s) already on this screen",
                        style = MaterialTheme.typography.labelSmall,
                        color = ReadinessOrange
                    )
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
        },
        confirmButton = {
            Button(
                onClick = { if (noteText.isNotBlank()) onSubmit(selectedType, noteText) },
                enabled = noteText.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = EcgCyan,
                    contentColor = BackgroundDark,
                    disabledContainerColor = SurfaceVariant,
                    disabledContentColor = TextSecondary
                )
            ) {
                Text("Submit")
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
            ) {
                Text("Cancel")
            }
        }
    )
}
