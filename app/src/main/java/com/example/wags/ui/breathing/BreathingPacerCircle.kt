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
import com.example.wags.ui.theme.BackgroundDark
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
 * - When the inhale circle reaches maximum size (progress ≈ 1.0), it turns **white**
 *   to visually signal the transition to exhale.
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
 * @param onPhaseTransition optional callback fired exactly once each time [isInhaling] changes.
 *                          Used to trigger haptic feedback at the breath transition.
 */
@Composable
fun BreathingPacerCircle(
    progress: Float,
    isInhaling: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 260.dp,
    showLabel: Boolean = true,
    overlayLabel: String? = null,
    onPhaseTransition: ((isInhaling: Boolean) -> Unit)? = null
) {
    // Fire the callback exactly once per phase change
    var lastPhase by remember { mutableStateOf(isInhaling) }
    LaunchedEffect(isInhaling) {
        if (isInhaling != lastPhase) {
            lastPhase = isInhaling
            onPhaseTransition?.invoke(isInhaling)
        }
    }

    // Progress 0.0 = inner circle completely gone, 1.0 = fills the outer circle
    val clampedProgress = progress.coerceIn(0f, 1f)

    // When inhaling and progress is near max (≥ 0.95), flash the circle white
    val isAtPeak = isInhaling && clampedProgress >= 0.95f

    // Base color: light for inhale, dark for exhale; white flash at peak
    val baseColor = when {
        isAtPeak    -> Color.White
        isInhaling  -> PacerInhale
        else        -> PacerExhale
    }
    val color by animateColorAsState(
        targetValue = baseColor,
        animationSpec = tween(durationMillis = 150),
        label = "pacer_circle_color"
    )

    val label = overlayLabel ?: if (isInhaling) "INHALE" else "EXHALE"

    // Text color: dark on white/bright circle, light on dim background
    val targetTextColor = when {
        isAtPeak && clampedProgress > 0.45f -> BackgroundDark   // dark text on white circle
        clampedProgress > 0.45f             -> TextPrimary      // light text on filled circle
        else                                -> if (isInhaling) PacerInhale else PacerExhale
    }
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
        // circle is small) and a contrasting color (when the inner circle is large).
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
