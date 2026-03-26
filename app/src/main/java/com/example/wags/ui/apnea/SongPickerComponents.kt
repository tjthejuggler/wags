package com.example.wags.ui.apnea

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.wags.data.spotify.SpotifyTrackDetail
import com.example.wags.ui.theme.*

// ─────────────────────────────────────────────────────────────────────────────
// Song Picker Button
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Button shown on the free-hold screen (before the hold starts) when the user
 * has MUSIC selected and their Spotify account is connected.
 */
@Composable
fun SongPickerButton(onClick: () -> Unit) {
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
            Text("🎵  Choose a Song", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Selected Song Banner
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Banner shown before the hold starts when the user has selected a song
 * from the picker. Shows the song name/artist and a clear button.
 */
@Composable
fun SelectedSongBanner(track: SpotifyTrackDetail, onClear: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = SurfaceDark,
        tonalElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("🎵", style = MaterialTheme.typography.bodyMedium)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    track.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    track.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            TextButton(onClick = onClear) {
                Text("✕", color = TextSecondary)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Song Picker Dialog
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Stable identity key for a song card — uses URI when available, otherwise title+artist.
 */
private fun SpotifyTrackDetail.cardKey(): String =
    if (spotifyUri.isNotBlank()) spotifyUri else "$title|$artist"

/**
 * Full-screen dialog showing previously-played songs as cards.
 * The user taps a card to load that song into Spotify playback.
 */
@Composable
fun SongPickerDialog(
    songs: List<SpotifyTrackDetail>,
    isLoading: Boolean,
    selectedSong: SpotifyTrackDetail?,
    loadingSelectedSong: Boolean,
    onSongSelected: (SpotifyTrackDetail) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BackgroundDark,
        title = {
            Text(
                "Choose a Song",
                style = MaterialTheme.typography.titleLarge,
                color = TextPrimary
            )
        },
        text = {
            when {
                isLoading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = EcgCyan)
                    }
                }
                songs.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "No songs recorded yet.\nDo a breath hold with Music to build your library!",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 400.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(songs, key = { it.cardKey() }) { track ->
                            SongCard(
                                track = track,
                                isSelected = selectedSong?.cardKey() == track.cardKey(),
                                isLoading = loadingSelectedSong &&
                                    selectedSong?.cardKey() == track.cardKey(),
                                onClick = { onSongSelected(track) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    if (selectedSong != null) "Done" else "Cancel",
                    color = TextSecondary
                )
            }
        },
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Song Card
// ─────────────────────────────────────────────────────────────────────────────

/**
 * A card representing a single song in the picker.
 * Shows title, artist, and duration. Highlights when selected.
 */
@Composable
private fun SongCard(
    track: SpotifyTrackDetail,
    isSelected: Boolean,
    isLoading: Boolean,
    onClick: () -> Unit
) {
    val borderColor = if (isSelected) TextSecondary else SurfaceVariant
    val bgColor = if (isSelected) SurfaceVariant else SurfaceDark

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isLoading) { onClick() },
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
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Music icon / loading indicator
            Box(
                modifier = Modifier.size(40.dp),
                contentAlignment = Alignment.Center
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = TextSecondary,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(
                        "🎵",
                        style = MaterialTheme.typography.headlineSmall
                    )
                }
            }

            // Song info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    track.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isSelected) TextPrimary else TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    track.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Duration
            if (track.durationMs > 0) {
                Text(
                    formatDurationMs(track.durationMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }

            // Selected indicator
            if (isSelected && !isLoading) {
                Text("✓", color = TextPrimary, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────────

private fun formatDurationMs(ms: Long): String {
    val totalSeconds = ms / 1000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "${minutes}:${seconds.toString().padStart(2, '0')}"
}
