package com.example.wags.ui.breathing

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.wags.ui.theme.PacerExhale
import com.example.wags.ui.theme.PacerInhale

/**
 * Unified breathing pacer circle used across all resonance breathing screens.
 *
 * The animation is an expanding/shrinking inner circle:
 * - During **inhale**, the inner circle grows from nothing (0) to completely fill the outer circle (1).
 * - During **exhale**, the inner circle shrinks from full (1) back to nothing (0).
 * - The color changes at the exact transition points:
 *   - Inhale color while expanding (progress 0→1)
 *   - Exhale color while shrinking (progress 1→0)
 * - The label ("INHALE"/"EXHALE") changes at the exact same transition points.
 *
 * @param progress 0.0 = inner circle gone (start of inhale / end of exhale),
 *                 1.0 = inner circle fills outer circle (end of inhale / start of exhale).
 *                 This value is driven by [ContinuousPacerEngine.getPacerRadius].
 * @param isInhaling true when in the inhale phase, false when exhaling.
 *                   Drives both the color and the label.
 * @param size the overall size of the pacer circle.
 * @param showLabel whether to show the INHALE/EXHALE label (default true).
 * @param overlayLabel optional label to show instead of INHALE/EXHALE (e.g. "BASELINE", "WASHOUT").
 */
@Composable
fun BreathingPacerCircle(
    progress: Float,
    isInhaling: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 260.dp,
    showLabel: Boolean = true,
    overlayLabel: String? = null
) {
    // Color is determined by the current phase
    val color = if (isInhaling) PacerInhale else PacerExhale
    val label = overlayLabel ?: if (isInhaling) "INHALE" else "EXHALE"

    // Progress 0.0 = inner circle completely gone, 1.0 = fills the outer circle
    val clampedProgress = progress.coerceIn(0f, 1f)

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.size(size)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val outerRadius = this.size.minDimension / 2f

            // Outer circle (track) — always visible, dim
            drawCircle(
                color = color.copy(alpha = 0.15f),
                radius = outerRadius
            )

            // Inner circle — scales from 0 to outerRadius based on progress
            if (clampedProgress > 0.001f) {
                drawCircle(
                    color = color,
                    radius = outerRadius * clampedProgress
                )
            }
        }

        if (showLabel) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleLarge,
                color = if (clampedProgress > 0.5f) {
                    // When inner circle is large, use contrasting color for readability
                    Color.White
                } else {
                    color
                }
            )
        }
    }
}
