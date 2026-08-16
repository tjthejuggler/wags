package com.example.wags.ui.meditation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.wags.data.meditation.AudioImportCategory
import com.example.wags.data.meditation.AudioImportService
import com.example.wags.data.meditation.AudioImportUiState
import com.example.wags.ui.theme.BackgroundDark
import com.example.wags.ui.theme.SurfaceVariant
import com.example.wags.ui.theme.TextDisabled
import com.example.wags.ui.theme.TextPrimary
import com.example.wags.ui.theme.TextSecondary
import java.util.Locale

/**
 * Share-to-Wags YouTube audio import screen. Reached by sharing a YouTube
 * link from any app (e.g. the YouTube share sheet) to Wags.
 *
 * The user first picks a category (Meditation / NSDR or Guided Apnea); the
 * import itself then runs in [AudioImportService], so the download continues
 * in the background even after the app is closed. While the app is open this
 * screen shows live progress from the service.
 */
@Composable
fun AudioImportScreen(
    onNavigateBack: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val state by AudioImportService.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    fun beginImport(category: AudioImportCategory) {
        val url = AudioImportBus.consumePendingUrl() ?: return
        AudioImportService.start(context, url, category)
    }

    Surface(color = BackgroundDark, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("🎧", style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.height(8.dp))
            Text(
                "Import YouTube Audio",
                style = MaterialTheme.typography.titleLarge,
                color = TextPrimary,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(24.dp))

            when (val s = state) {
                is AudioImportUiState.Resolving -> {
                    CircularProgressIndicator(color = TextSecondary)
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Fetching video info…",
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                is AudioImportUiState.Downloading -> {
                    Text(
                        s.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        textAlign = TextAlign.Center
                    )
                    if (!s.channel.isNullOrBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "▶ ${s.channel}",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    if (s.durationSeconds > 0) {
                        Text(
                            formatDuration(s.durationSeconds),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    Spacer(Modifier.height(20.dp))
                    val progress = s.progress
                    if (progress != null) {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth(),
                            color = TextSecondary
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = TextSecondary
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (s.totalBytes > 0)
                            "${formatBytes(s.bytesDownloaded)} / ${formatBytes(s.totalBytes)}"
                        else
                            formatBytes(s.bytesDownloaded),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "You can close the app — the download continues in the background.",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextDisabled,
                        textAlign = TextAlign.Center
                    )
                }

                is AudioImportUiState.Success -> {
                    Text("✓", style = MaterialTheme.typography.displayMedium, color = TextPrimary)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        s.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        when (s.category) {
                            AudioImportCategory.MEDITATION ->
                                "Saved to your meditation audio folder"
                            AudioImportCategory.APNEA ->
                                "Added to your guided apnea audio library"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(24.dp))
                    Button(
                        onClick = onNavigateBack,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SurfaceVariant,
                            contentColor = TextPrimary
                        )
                    ) {
                        Text("Done")
                    }
                }

                is AudioImportUiState.Failed -> {
                    Text("⚠️", style = MaterialTheme.typography.displayMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        s.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(24.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = onNavigateBack) {
                            Text("Close", color = TextPrimary)
                        }
                        if (s.message.contains("folder", ignoreCase = true)) {
                            Button(
                                onClick = onNavigateToSettings,
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

                AudioImportUiState.Idle -> {
                    if (AudioImportBus.pendingUrl == null) {
                        Text(
                            "No YouTube link received.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(24.dp))
                        OutlinedButton(onClick = onNavigateBack) {
                            Text("Close", color = TextPrimary)
                        }
                    } else {
                        Text(
                            "What is this audio for?",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary
                        )
                        Spacer(Modifier.height(20.dp))
                        CategoryCard(
                            emoji = "🧘",
                            title = "Meditation / NSDR",
                            subtitle = "Saved to the meditation audio folder"
                        ) { beginImport(AudioImportCategory.MEDITATION) }
                        Spacer(Modifier.height(12.dp))
                        CategoryCard(
                            emoji = "🤿",
                            title = "Guided Apnea",
                            subtitle = "Added to the guided audio library"
                        ) { beginImport(AudioImportCategory.APNEA) }
                    }
                }
            }
        }
    }
}

// ── Category chooser card ─────────────────────────────────────────────────────

@Composable
private fun CategoryCard(
    emoji: String,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(
            containerColor = SurfaceVariant,
            contentColor = TextPrimary
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(emoji, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(4.dp))
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%d:%02d", m, s)
}

private fun formatBytes(bytes: Long): String {
    return if (bytes < 1024 * 1024) "${bytes / 1024} KB"
    else String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
}
