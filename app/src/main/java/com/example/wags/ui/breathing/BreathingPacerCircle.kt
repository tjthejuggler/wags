package com.example.wags.ui.breathing

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.wags.ui.theme.PacerExhale
import com.example.wags.ui.theme.PacerInhale
import com.example.wags.ui.theme.TextPrimary

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
 * The label text is **always visible** — it never disappears or gets recreated.
 * It smoothly transitions between a contrasting color on the filled circle and
 * the phase color on the dim track background.
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
    // Color is determined by the current phase — greyscale: light for inhale, dark for exhale
    val color = if (isInhaling) PacerInhale else PacerExhale
    val label = overlayLabel ?: if (isInhaling) "INHALE" else "EXHALE"

    // Progress 0.0 = inner circle completely gone, 1.0 = fills the outer circle
    val clampedProgress = progress.coerceIn(0f, 1f)

    // Smoothly animate the text color so it never "pops" or appears to be
    // removed and recreated. The target color is white when the filled circle
    // is large enough to be behind the text, otherwise the phase color.
    val targetTextColor = if (clampedProgress > 0.45f) TextPrimary else color
    val animatedTextColor by animateColorAsState(
        targetValue = targetTextColor,
        animationSpec = tween(durationMillis = 300),
        label = "pacer_text_color"
    )

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

        // Label is always visible and never removed from the composition tree.
        // The color animates smoothly between the phase color (when the inner
        // circle is small) and white (when the inner circle is large).
        if (showLabel) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.SemiBold
                ),
                color = animatedTextColor
            )
        }
    }
}
