package com.example.wags.ui.meditation

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.wags.data.db.entity.MeditationAudioEntity
import com.example.wags.ui.common.grayscale
import com.example.wags.ui.theme.*

// ── Picker Button ────────────────────────────────────────────────────────────

/**
 * Button shown on the meditation idle screen to open the audio picker dialog.
 * Styled like [com.example.wags.ui.apnea.GuidedAudioPickerButton].
 */
@Composable
fun MeditationAudioPickerButton(onClick: () -> Unit) {
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
                "🎧  Choose Audio",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.grayscale()
            )
        }
    }
}

// ── Selected Audio Banner ────────────────────────────────────────────────────

/**
 * Banner showing the currently selected audio name.
 * Displayed above the picker button when an audio is selected.
 */
@Composable
fun SelectedMeditationAudioBanner(name: String) {
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

// ── Picker Dialog ────────────────────────────────────────────────────────────

/**
 * Dialog showing all meditation/NSDR audios with channel filter chips.
 * Follows the same pattern as [com.example.wags.ui.apnea.GuidedAudioPickerDialog].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeditationAudioPickerDialog(
    audios: List<MeditationAudioEntity>,
    selectedAudio: MeditationAudioEntity?,
    availableChannels: List<String>,
    selectedChannelFilter: String?,
    isLoadingAudios: Boolean,
    audioDirUri: String,
    onSelectAudio: (MeditationAudioEntity) -> Unit,
    onChannelFilterSelected: (String?) -> Unit,
    onRefreshAudios: () -> Unit,
    onEditAudioUrl: (MeditationAudioEntity) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BackgroundDark,
        title = {
            Text(
                "Choose Audio",
                style = MaterialTheme.typography.titleLarge,
                color = TextPrimary
            )
        },
        text = {
            Column {
                // Audio directory hint
                if (audioDirUri.isBlank()) {
                    Card(colors = CardDefaults.cardColors(containerColor = SurfaceVariant)) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("ℹ️", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "No audio folder set. Go to Settings → Meditation Audio Directory to choose a folder.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                // Refresh button row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    if (isLoadingAudios) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = TextSecondary,
                            strokeWidth = 2.dp
                        )
                    } else {
                        TextButton(onClick = onRefreshAudios) {
                            Text("Refresh", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                        }
                    }
                }

                // Channel filter chips
                if (availableChannels.isNotEmpty()) {
                    ChannelFilterChips(
                        channels = availableChannels,
                        selectedChannel = selectedChannelFilter,
                        onChannelSelected = onChannelFilterSelected
                    )
                    Spacer(Modifier.height(8.dp))
                }

                // Filtered audio list
                val filteredAudios = when {
                    selectedChannelFilter == null -> audios
                    else -> audios.filter { it.isNone || it.youtubeChannel == selectedChannelFilter }
                }

                if (filteredAudios.isEmpty() && !isLoadingAudios) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (audios.isEmpty())
                                "No audio files found.\nAdd audio files to your chosen folder and tap Refresh."
                            else
                                "No audios match the selected filter.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextDisabled,
                            textAlign = TextAlign.Center,
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
                        items(filteredAudios, key = { it.audioId }) { audio ->
                            AudioPickerCard(
                                audio = audio,
                                isSelected = selectedAudio?.audioId == audio.audioId,
                                onSelect = {
                                    onSelectAudio(audio)
                                    onDismiss()
                                },
                                onEditUrl = { onEditAudioUrl(audio) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    if (selectedAudio != null) "Done" else "Cancel",
                    color = TextSecondary
                )
            }
        }
    )
}

// ── Channel filter chips (inside dialog) ──────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChannelFilterChips(
    channels: List<String>,
    selectedChannel: String?,
    onChannelSelected: (String?) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "Filter by channel",
            style = MaterialTheme.typography.labelMedium,
            color = TextSecondary
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = selectedChannel == null,
                onClick = { onChannelSelected(null) },
                label = { Text("All") },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = TextSecondary.copy(alpha = 0.2f),
                    selectedLabelColor = TextPrimary
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = selectedChannel == null,
                    selectedBorderColor = TextSecondary,
                    borderColor = SurfaceVariant
                )
            )
            channels.forEach { channel ->
                FilterChip(
                    selected = selectedChannel == channel,
                    onClick = { onChannelSelected(if (selectedChannel == channel) null else channel) },
                    label = {
                        Text(
                            channel,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = TextSecondary.copy(alpha = 0.2f),
                        selectedLabelColor = TextPrimary
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = selectedChannel == channel,
                        selectedBorderColor = TextSecondary,
                        borderColor = SurfaceVariant
                    )
                )
            }
        }
    }
}

// ── Audio picker card (inside dialog) ─────────────────────────────────────────

@Composable
private fun AudioPickerCard(
    audio: MeditationAudioEntity,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onEditUrl: () -> Unit
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
            .clickable(onClick = onSelect),
        colors = CardDefaults.cardColors(containerColor = bgColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                // Title row: checkmark + display name
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (isSelected) {
                        Text("✓", color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(
                        text = when {
                            audio.isNone -> "None  (silent meditation)"
                            else -> audio.displayName
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextPrimary,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Channel badge (YouTube audios only)
                if (!audio.isNone && !audio.youtubeChannel.isNullOrBlank()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            "▶",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                        Text(
                            text = audio.youtubeChannel,
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // For non-YouTube audios that have a URL, show the URL as a hint
                if (!audio.isNone && audio.youtubeChannel.isNullOrBlank() && audio.sourceUrl.isNotBlank()) {
                    Text(
                        text = audio.sourceUrl,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextDisabled,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // For non-YouTube audios, show the filename as a subtitle when the display name
                // is the YouTube title (i.e. they differ)
                if (!audio.isNone && audio.youtubeTitle != null && audio.fileName.isNotBlank()) {
                    Text(
                        text = audio.fileName,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextDisabled,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Edit URL button (not shown for None)
            if (!audio.isNone) {
                IconButton(
                    onClick = onEditUrl,
                    modifier = Modifier.size(32.dp)
                ) {
                    Text("✏️", style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.grayscale())
                }
            }
        }
    }
}
