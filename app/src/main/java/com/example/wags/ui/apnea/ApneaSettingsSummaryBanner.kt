package com.example.wags.ui.apnea

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.wags.ui.theme.TextSecondary

/**
 * Small one-line text banner showing the current apnea settings.
 * Displayed just below the top bar on active session screens so the user
 * always knows which settings are in effect.
 *
 * @param lungVolume  Raw lung-volume key (e.g. "FULL", "PARTIAL", "EMPTY").
 * @param prepType    Raw prep-type key (e.g. "NO_PREP", "RESONANCE", "HYPER").
 * @param timeOfDay   Raw time-of-day key (e.g. "MORNING", "DAY", "NIGHT").
 * @param posture     Raw posture key (e.g. "SITTING", "LAYING").
 * @param audio       Raw audio key (e.g. "SILENCE", "MUSIC").
 */
@Composable
fun ApneaSettingsSummaryBanner(
    lungVolume: String,
    prepType: String,
    timeOfDay: String,
    posture: String,
    audio: String,
    modifier: Modifier = Modifier
) {
    val summary = buildString {
        append(lungVolume.displayLungVolume())
        append(" · ")
        append(prepType.displayPrepType())
        append(" · ")
        append(timeOfDay.displayTimeOfDay())
        append(" · ")
        append(posture.displayPosture())
        append(" · ")
        append(audio.displayAudio())
    }

    Text(
        text = summary,
        style = MaterialTheme.typography.labelSmall,
        color = TextSecondary,
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    )
}

// ── Display helpers for raw string keys ──────────────────────────────────────

private fun String.displayPrepType(): String = when (uppercase()) {
    "NO_PREP"   -> "No Prep"
    "RESONANCE" -> "Resonance"
    "HYPER"     -> "Hyper"
    else        -> lowercase().replaceFirstChar { it.uppercase() }
}

private fun String.displayTimeOfDay(): String = when (uppercase()) {
    "MORNING" -> "Morning"
    "DAY"     -> "Day"
    "NIGHT"   -> "Night"
    else      -> lowercase().replaceFirstChar { it.uppercase() }
}

private fun String.displayPosture(): String = when (uppercase()) {
    "SITTING" -> "Sitting"
    "LAYING"  -> "Laying"
    else      -> lowercase().replaceFirstChar { it.uppercase() }
}

private fun String.displayAudio(): String = when (uppercase()) {
    "SILENCE" -> "Silence"
    "MUSIC"   -> "Music"
    else      -> lowercase().replaceFirstChar { it.uppercase() }
}
