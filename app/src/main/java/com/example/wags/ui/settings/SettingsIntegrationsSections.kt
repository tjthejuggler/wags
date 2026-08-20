package com.example.wags.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.wags.ui.theme.ReadinessGreen
import com.example.wags.ui.theme.ReadinessOrange
import com.example.wags.ui.theme.SurfaceVariant
import com.example.wags.ui.theme.TextPrimary
import com.example.wags.ui.theme.TextSecondary

// ── Meditation audio folder sub-section ───────────────────────────────────────

@Composable
fun MeditationAudioDirectorySection(
    dirUri: String,
    onChooseDirectory: () -> Unit,
    onClearDirectory: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SettingsSubSectionLabel("Meditation Audio Folder")
        Text(
            "Choose the folder that contains your meditation / NSDR audio files. " +
                "The app will scan this folder and list all audio files in the Meditation screen.",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                if (dirUri.isNotBlank()) {
                    val displayPath = try {
                        Uri.parse(dirUri).lastPathSegment ?: dirUri
                    } catch (_: Exception) { dirUri }
                    Text(
                        displayPath,
                        style = MaterialTheme.typography.bodySmall,
                        color = ReadinessGreen
                    )
                } else {
                    Text(
                        "No folder selected",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (dirUri.isNotBlank()) {
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
                        if (dirUri.isNotBlank()) "Change" else "Choose Folder",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

// ── Spotify sub-section ───────────────────────────────────────────────────────

@Composable
fun SpotifySection(
    spotifyConnected: Boolean,
    onConnectSpotify: () -> Unit,
    onDisconnectSpotify: () -> Unit
) {
    val context = LocalContext.current
    val isGranted = remember {
        android.service.notification.NotificationListenerService::class.java.let {
            androidx.core.app.NotificationManagerCompat
                .getEnabledListenerPackages(context)
                .contains(context.packageName)
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SettingsSubSectionLabel("Spotify")
        Text(
            "Connect your Spotify account to load songs directly into playback " +
                "before a breath hold. Song detection also records what played during holds.",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )

        // ── Spotify Account ──────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Spotify Account", style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (spotifyConnected) "✓ Connected" else "Not connected",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (spotifyConnected) ReadinessGreen else TextSecondary
                )
            }
            if (spotifyConnected) {
                OutlinedButton(
                    onClick = onDisconnectSpotify,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary)
                ) {
                    Text("Disconnect")
                }
            } else {
                Button(
                    onClick = onConnectSpotify,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SurfaceVariant,
                        contentColor = TextPrimary
                    )
                ) {
                    Text("Connect Spotify")
                }
            }
        }

        SettingsSubSectionDivider()

        // ── Notification Access (song detection) ─────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Notification Access", style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (isGranted) "✓ Granted" else "⚠ Required for song detection",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isGranted) ReadinessGreen else ReadinessOrange
                )
            }
            Button(
                onClick = {
                    context.startActivity(
                        Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = SurfaceVariant,
                    contentColor = TextPrimary
                )
            ) {
                Text("Open Settings")
            }
        }
    }
}
