package com.example.wags.ui.apnea

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.example.wags.ui.theme.TextSecondary

/**
 * Small one-line text banner showing the current apnea settings.
 * Displayed just below the top bar on active session screens so the user
 * always knows which settings are in effect.
 *
 * When [onClick] is provided the text is clickable (underlined) to hint
 * that tapping opens a settings-edit popup.
 *
 * @param lungVolume  Raw lung-volume key (e.g. "FULL", "PARTIAL", "EMPTY").
 * @param prepType    Raw prep-type key (e.g. "NO_PREP", "RESONANCE", "HYPER").
 * @param timeOfDay   Raw time-of-day key (e.g. "MORNING", "DAY", "NIGHT").
 * @param posture     Raw posture key (e.g. "SITTING", "LAYING").
 * @param audio       Raw audio key (e.g. "SILENCE", "MUSIC").
 * @param onClick     Optional click handler — when non-null the banner becomes tappable.
 */
@Composable
fun ApneaSettingsSummaryBanner(
    lungVolume: String,
    prepType: String,
    timeOfDay: String,
    posture: String,
    audio: String,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val summary = buildString {
        append(lungVolume.displayLungVolumeBanner())
        append(" · ")
        append(prepType.displayPrepTypeBanner())
        append(" · ")
        append(timeOfDay.displayTimeOfDayBanner())
        append(" · ")
        append(posture.displayPostureBanner())
        append(" · ")
        append(audio.displayAudioBanner())
    }

    Text(
        text = summary,
        style = MaterialTheme.typography.labelSmall,
        color = TextSecondary,
        textAlign = TextAlign.Center,
        textDecoration = if (onClick != null) TextDecoration.Underline else TextDecoration.None,
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 4.dp)
    )
}

// ── Display helpers for raw string keys ──────────────────────────────────────

internal fun String.displayPrepTypeBanner(): String = when (uppercase()) {
    "NO_PREP"   -> "No Prep"
    "RESONANCE" -> "Resonance"
    "HYPER"     -> "Hyper"
    else        -> lowercase().replaceFirstChar { it.uppercase() }
}

internal fun String.displayTimeOfDayBanner(): String = when (uppercase()) {
    "MORNING" -> "Morning"
    "DAY"     -> "Day"
    "NIGHT"   -> "Night"
    else      -> lowercase().replaceFirstChar { it.uppercase() }
}

internal fun String.displayPostureBanner(): String = when (uppercase()) {
    "SITTING" -> "Sitting"
    "LAYING"  -> "Laying"
    else      -> lowercase().replaceFirstChar { it.uppercase() }
}

internal fun String.displayAudioBanner(): String = when (uppercase()) {
    "SILENCE" -> "Silence"
    "MUSIC"   -> "Music"
    "MOVIE"   -> "Movie"
    else      -> lowercase().replaceFirstChar { it.uppercase() }
}

internal fun String.displayLungVolumeBanner(): String = when (uppercase()) {
    "PARTIAL" -> "Half"
    else      -> lowercase().replaceFirstChar { it.uppercase() }
}
