package com.example.wags.ui.apnea

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.wags.data.spotify.SpotifyTrackDetail
import com.example.wags.ui.common.grayscale
import com.example.wags.ui.theme.*

// ─────────────────────────────────────────────────────────────────────────────
// Sort options
// ─────────────────────────────────────────────────────────────────────────────

/** Available sort criteria for the song picker list. */
enum class SongSortOption(val label: String) {
    RECENT("Recent"),
    TITLE("Title"),
    ARTIST("Artist"),
    LENGTH("Length")
}

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
            Text("🎵  Choose a Song", style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.grayscale())
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Spotify Connect Prompt
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Shown in place of the song picker when MUSIC is selected but Spotify is not connected.
 * Tapping it navigates to Settings where the user can connect their Spotify account.
 */
@Composable
fun SpotifyConnectPrompt(onNavigateToSettings: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onNavigateToSettings),
        color = SurfaceDark,
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("🎵", style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.grayscale())
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Spotify not connected",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary
                )
                Text(
                    "Tap to connect in Settings",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
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
            Text("🎵", style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.grayscale())
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
    songCompletionStatus: Map<String, SongCompletionStatus> = emptyMap(),
    onSongSelected: (SpotifyTrackDetail) -> Unit,
    onDismiss: () -> Unit
) {
    // Sort state
    var sortOption by remember { mutableStateOf(SongSortOption.RECENT) }
    var sortAscending by remember { mutableStateOf(false) } // false = descending (most recent first)

    val sortedSongs = remember(songs, sortOption, sortAscending) {
        val sorted = when (sortOption) {
            SongSortOption.RECENT -> songs // already ordered by most recent from the loader
            SongSortOption.TITLE -> songs.sortedBy { it.title.lowercase() }
            SongSortOption.ARTIST -> songs.sortedBy { it.artist.lowercase() }
            SongSortOption.LENGTH -> songs.sortedBy { it.durationMs }
        }
        // For RECENT, default descending = most recent first (original order).
        // For others, default ascending = A→Z / shortest first.
        // The ascending flag is toggled relative to the natural order.
        if (sortOption == SongSortOption.RECENT) {
            if (sortAscending) sorted.reversed() else sorted
        } else {
            if (sortAscending) sorted else sorted.reversed()
        }
    }

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
            Column {
                // Sort chips row — only show when there are songs to sort
                if (!isLoading && songs.isNotEmpty()) {
                    SortChipRow(
                        currentSort = sortOption,
                        ascending = sortAscending,
                        onSortChanged = { option ->
                            if (option == sortOption) {
                                // Tapping the same chip toggles direction
                                sortAscending = !sortAscending
                            } else {
                                sortOption = option
                                // Default direction: Recent → descending, others → ascending
                                sortAscending = option != SongSortOption.RECENT
                            }
                        }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

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
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(sortedSongs, key = { it.cardKey() }) { track ->
                                val key = "${track.title.lowercase().trim()}|${track.artist.lowercase().trim()}"
                                val completion = songCompletionStatus[key]
                                SongCard(
                                    track = track,
                                    isSelected = selectedSong?.cardKey() == track.cardKey(),
                                    isLoading = loadingSelectedSong &&
                                        selectedSong?.cardKey() == track.cardKey(),
                                    completedEver = completion?.completedEver == true,
                                    completedWithCurrentSettings = completion?.completedWithCurrentSettings == true,
                                    onClick = { onSongSelected(track) }
                                )
                            }
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
// Sort Chip Row
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun SortChipRow(
    currentSort: SongSortOption,
    ascending: Boolean,
    onSortChanged: (SongSortOption) -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        SongSortOption.entries.forEach { option ->
            val isActive = option == currentSort
            val arrow = if (isActive) if (ascending) " ↑" else " ↓" else ""
            val bgColor = if (isActive) SurfaceVariant else SurfaceDark
            val textColor = if (isActive) TextPrimary else TextSecondary

            Surface(
                onClick = { onSortChanged(option) },
                shape = MaterialTheme.shapes.small,
                color = bgColor,
                border = BorderStroke(
                    width = 1.dp,
                    color = if (isActive) TextSecondary else SurfaceVariant
                )
            ) {
                Text(
                    text = "${option.label}$arrow",
                    style = MaterialTheme.typography.labelSmall,
                    color = textColor,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Song Card
// ─────────────────────────────────────────────────────────────────────────────

/**
 * A compact card representing a single song in the picker.
 * Shows album art, title, artist, duration, and completion checkmarks.
 *
 * Checkmarks:
 * - [completedEver] → bright checkmark (✓) — song was completed during any past free hold.
 * - [completedWithCurrentSettings] → lighter grey checkmark (✓) — completed with current settings.
 * A card can show 0, 1, or 2 checkmarks.
 */
@Composable
private fun SongCard(
    track: SpotifyTrackDetail,
    isSelected: Boolean,
    isLoading: Boolean,
    completedEver: Boolean = false,
    completedWithCurrentSettings: Boolean = false,
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
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Album art / loading indicator
            Box(
                modifier = Modifier.size(36.dp),
                contentAlignment = Alignment.Center
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = TextSecondary,
                        strokeWidth = 2.dp
                    )
                } else if (!track.albumArt.isNullOrBlank()) {
                    AsyncImage(
                        model = track.albumArt,
                        contentDescription = "${track.title} album art",
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Text(
                        "🎵",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.grayscale()
                    )
                }
            }

            // Song info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    track.title,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isSelected) TextPrimary else TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    track.artist,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Duration
            if (track.durationMs > 0) {
                Text(
                    formatDurationMs(track.durationMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }

            // Completion checkmarks — shown to the left of the selected indicator
            // Bright checkmark = completed during any past free hold
            // Lighter grey checkmark = completed with current settings
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

/**
 * Deduplicates a list of [SpotifyTrackDetail] by title+artist (case-insensitive).
 * When duplicates exist, prefers the entry with a non-blank spotifyUri and albumArt.
 */
fun deduplicateTracks(tracks: List<SpotifyTrackDetail>): List<SpotifyTrackDetail> {
    val seen = mutableMapOf<String, SpotifyTrackDetail>()
    for (track in tracks) {
        val key = "${track.title.lowercase().trim()}|${track.artist.lowercase().trim()}"
        val existing = seen[key]
        if (existing == null) {
            seen[key] = track
        } else {
            // Prefer the entry with more metadata (URI, album art, duration)
            val better = if (track.spotifyUri.isNotBlank() && existing.spotifyUri.isBlank()) {
                track
            } else if (!track.albumArt.isNullOrBlank() && existing.albumArt.isNullOrBlank()) {
                track
            } else if (track.durationMs > 0 && existing.durationMs == 0L) {
                track
            } else {
                existing
            }
            seen[key] = better
        }
    }
    return seen.values.toList()
}
