package com.example.wags.ui.apnea

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.wags.data.db.entity.GuidedAudioEntity
import com.example.wags.ui.common.grayscale
import com.example.wags.ui.theme.*

// ─────────────────────────────────────────────────────────────────────────────
// Guided audio completion status
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Tracks whether a guided audio was used during past apnea sessions.
 * @property completedEver true if the audio was used during any past session.
 * @property completedWithCurrentSettings true if used with the current 5-setting combination.
 */
data class GuidedCompletionStatus(
    val completedEver: Boolean = false,
    val completedWithCurrentSettings: Boolean = false
)

// ─────────────────────────────────────────────────────────────────────────────
// Guided Audio Picker Button
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Button shown on apnea setup screens when audio = GUIDED.
 * Styled like [SongPickerButton] but says "Choose a guided MP3".
 */
@Composable
fun GuidedAudioPickerButton(onClick: () -> Unit) {
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
                "🎧  Choose a guided MP3",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.grayscale()
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Selected Guided Audio Banner
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Banner showing the currently selected guided audio name.
 * Displayed above the picker button when a guided audio is selected.
 */
@Composable
fun SelectedGuidedAudioBanner(name: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = SurfaceVariant,
        tonalElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "🎧",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.grayscale()
            )
            Text(
                name,
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Guided Audio Picker Dialog
// ─────────────────────────────────────────────────────────────────────────────

/** Internal view state for the dialog. */
private enum class GuidedDialogView { LIST, ADD_NEW, DETAIL }

/**
 * Popup dialog for managing the guided audio library.
 *
 * - **LIST** view: scrollable library with "Add New MP3" button, tap to select, long-press for detail.
 * - **ADD_NEW** view: file picker + optional source URL + Save/Cancel.
 * - **DETAIL** view: file name, source URL with copy, delete, back.
 *
 * @param audios       All guided audios in the library.
 * @param selectedId   The currently selected audio's [GuidedAudioEntity.audioId] (0 if none).
 * @param onSelect     Called when the user taps an audio to select it.
 * @param onAddNew     Called when the user saves a new MP3: (uri, fileName, sourceUrl).
 * @param onDelete     Called when the user deletes an audio from the library.
 * @param onDismiss    Called to close the dialog.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GuidedAudioPickerDialog(
    audios: List<GuidedAudioEntity>,
    selectedId: Long,
    completionStatuses: Map<Long, GuidedCompletionStatus> = emptyMap(),
    onSelect: (GuidedAudioEntity) -> Unit,
    onAddNew: (uri: String, fileName: String, sourceUrl: String) -> Unit,
    onDelete: (GuidedAudioEntity) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    // Internal navigation state
    var currentView by remember { mutableStateOf(GuidedDialogView.LIST) }
    var detailAudio by remember { mutableStateOf<GuidedAudioEntity?>(null) }

    // ADD_NEW view state
    var newFileUri by remember { mutableStateOf("") }
    var newFileName by remember { mutableStateOf("") }
    var newSourceUrl by remember { mutableStateOf("") }

    // File picker launcher for ADD_NEW view
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) { /* best effort */ }
            newFileUri = uri.toString()
            newFileName = resolveDisplayName(context, uri)
        }
    }

    val title = when (currentView) {
        GuidedDialogView.LIST -> "Guided Audio Library"
        GuidedDialogView.ADD_NEW -> "Add Guided MP3"
        GuidedDialogView.DETAIL -> detailAudio?.fileName ?: "Detail"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BackgroundDark,
        title = {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        text = {
            when (currentView) {
                // ── LIST VIEW ────────────────────────────────────────────
                GuidedDialogView.LIST -> {
                    Column {
                        // "Add New MP3" button at the top
                        FilledTonalButton(
                            onClick = {
                                newFileUri = ""
                                newFileName = ""
                                newSourceUrl = ""
                                currentView = GuidedDialogView.ADD_NEW
                            },
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = SurfaceVariant,
                                contentColor = TextPrimary
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "➕  Add New MP3",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.grayscale()
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        if (audios.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(120.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "No guided audios yet.\nTap \"Add New MP3\" to get started.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextSecondary,
                                    modifier = Modifier.padding(16.dp)
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 400.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                items(audios, key = { it.audioId }) { audio ->
                                    val completion = completionStatuses[audio.audioId]
                                    GuidedAudioCard(
                                        audio = audio,
                                        isSelected = audio.audioId == selectedId,
                                        completedEver = completion?.completedEver == true,
                                        completedWithCurrentSettings = completion?.completedWithCurrentSettings == true,
                                        onClick = {
                                            onSelect(audio)
                                            onDismiss()
                                        },
                                        onLongClick = {
                                            detailAudio = audio
                                            currentView = GuidedDialogView.DETAIL
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                // ── ADD NEW VIEW ─────────────────────────────────────────
                GuidedDialogView.ADD_NEW -> {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        // Browse button
                        FilledTonalButton(
                            onClick = { launcher.launch(arrayOf("audio/*")) },
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = SurfaceVariant,
                                contentColor = TextPrimary
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "Browse for MP3",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }

                        // Show selected file name
                        if (newFileName.isNotBlank()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    "🎧",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.grayscale()
                                )
                                Text(
                                    newFileName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextPrimary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        // Source URL field
                        OutlinedTextField(
                            value = newSourceUrl,
                            onValueChange = { newSourceUrl = it },
                            label = {
                                Text(
                                    "YouTube / source URL (optional)",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = MaterialTheme.typography.bodySmall.copy(color = TextPrimary),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = TextSecondary,
                                unfocusedBorderColor = SurfaceDark,
                                focusedLabelColor = TextSecondary,
                                unfocusedLabelColor = TextDisabled,
                                cursorColor = TextPrimary
                            )
                        )

                        // Save / Cancel row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                        ) {
                            TextButton(onClick = { currentView = GuidedDialogView.LIST }) {
                                Text("Cancel", color = TextSecondary)
                            }
                            Button(
                                onClick = {
                                    if (newFileUri.isNotBlank() && newFileName.isNotBlank()) {
                                        onAddNew(newFileUri, newFileName, newSourceUrl.trim())
                                        currentView = GuidedDialogView.LIST
                                    }
                                },
                                enabled = newFileUri.isNotBlank(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = SurfaceVariant,
                                    contentColor = TextPrimary,
                                    disabledContainerColor = SurfaceDark,
                                    disabledContentColor = TextDisabled
                                )
                            ) {
                                Text("Save")
                            }
                        }
                    }
                }

                // ── DETAIL VIEW ──────────────────────────────────────────
                GuidedDialogView.DETAIL -> {
                    val audio = detailAudio
                    if (audio != null) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            // File name
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    "🎧",
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.grayscale()
                                )
                                Text(
                                    audio.fileName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextPrimary,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            // Source URL (if present)
                            if (audio.sourceUrl.isNotBlank()) {
                                Text(
                                    "Source URL:",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary
                                )
                                Text(
                                    audio.sourceUrl,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextPrimary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                TextButton(
                                    onClick = {
                                        clipboardManager.setText(
                                            AnnotatedString(audio.sourceUrl)
                                        )
                                    }
                                ) {
                                    Text("Copy URL", color = TextSecondary)
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // Delete + Back row
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                            ) {
                                TextButton(onClick = { currentView = GuidedDialogView.LIST }) {
                                    Text("Back", color = TextSecondary)
                                }
                                Button(
                                    onClick = {
                                        onDelete(audio)
                                        currentView = GuidedDialogView.LIST
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = ButtonDanger,
                                        contentColor = TextPrimary
                                    )
                                ) {
                                    Text("Delete")
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (currentView == GuidedDialogView.LIST) {
                TextButton(onClick = onDismiss) {
                    Text("Done", color = TextSecondary)
                }
            }
        }
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Guided Audio Card (used in the LIST view)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * A compact card for a single guided audio in the library list.
 * Tap to select, long-press for detail view.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GuidedAudioCard(
    audio: GuidedAudioEntity,
    isSelected: Boolean,
    completedEver: Boolean = false,
    completedWithCurrentSettings: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val bgColor = if (isSelected) SurfaceVariant else SurfaceDark
    val borderColor = if (isSelected) TextSecondary else SurfaceVariant

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        border = CardDefaults.outlinedCardBorder().copy(
            width = if (isSelected) 2.dp else 1.dp,
            brush = androidx.compose.ui.graphics.SolidColor(borderColor)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "🎧",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.grayscale()
            )
            Text(
                audio.fileName,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isSelected) TextPrimary else TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            // Completion checkmarks — bright = used ever, grey = used with current settings
            if (completedEver || completedWithCurrentSettings) {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (completedEver) {
                        Text("✓", color = TextPrimary, fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelSmall)
                    }
                    if (completedWithCurrentSettings) {
                        Text("✓", color = Color(0xFF888888), fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────────

/** Resolves the display name of a content URI (typically the file name). */
internal fun resolveDisplayName(context: android.content.Context, uri: Uri): String {
    return try {
        context.contentResolver.query(
            uri,
            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) cursor.getString(idx) else null
            } else null
        } ?: uri.lastPathSegment ?: "Unknown"
    } catch (_: Exception) {
        uri.lastPathSegment ?: "Unknown"
    }
}
