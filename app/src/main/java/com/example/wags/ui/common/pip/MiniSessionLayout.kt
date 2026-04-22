package com.example.wags.ui.common.pip

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.*
import com.example.wags.ui.theme.*

/**
 * Root container for all PiP content — dark background, fills the PiP window.
 */
@Composable
fun PipRoot(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDark)
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        content = content
    )
}

/**
 * Large timer text for the PiP window (e.g. "1:23").
 */
@Composable
fun PipTimerText(text: String, color: Color = ApneaHold) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineLarge,
        fontWeight = FontWeight.Bold,
        color = color,
        textAlign = TextAlign.Center,
        fontSize = 28.sp
    )
}

/**
 * Small label text (phase name, round info, etc.).
 */
@Composable
fun PipLabel(text: String, color: Color = TextSecondary) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        textAlign = TextAlign.Center,
        fontSize = 10.sp
    )
}

/**
 * Read-only result card shown after a session ends in PiP mode.
 *
 * No "Again" button — the user taps the OS RemoteAction "Again" button
 * in the system overlay instead (Compose touch is blocked in PiP mode).
 *
 * @param headline   Primary result line (e.g. "2:34").
 * @param subline    Secondary info (e.g. "Free Hold").
 * @param trophies   Trophy emoji string (e.g. "🏆🏆") — empty string hides the row.
 */
@Composable
fun PipResultCard(
    headline: String,
    subline: String,
    trophies: String
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = headline,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
            textAlign = TextAlign.Center,
            fontSize = 22.sp
        )
        Text(
            text = subline,
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
            textAlign = TextAlign.Center,
            fontSize = 10.sp
        )
        if (trophies.isNotEmpty()) {
            Text(
                text = trophies,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                fontSize = 14.sp
            )
        }
        PipLabel("↺ tap Again", color = TextSecondary)
    }
}
