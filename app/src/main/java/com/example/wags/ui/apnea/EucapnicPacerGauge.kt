package com.example.wags.ui.apnea

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.wags.domain.model.EucapnicPhase
import com.example.wags.ui.theme.BackgroundDark
import com.example.wags.ui.theme.PacerExhale
import com.example.wags.ui.theme.PacerExhaleColor
import com.example.wags.ui.theme.PacerInhale
import com.example.wags.ui.theme.PacerInhaleColor
import com.example.wags.ui.theme.TextPrimary

/**
 * Visual breathing gauge for Eucapnic Diaphragmatic preparation.
 *
 * Renders an expanding/contracting circle driven by the pacer engine's radius:
 * - **INHALE**: circle expands from nothing to completely fill the outer circle
 * - **TOP_PAUSE**: circle holds at full expansion
 * - **EXHALE**: circle contracts from full back to nothing
 * - **BOTTOM_PAUSE**: circle holds at minimum, dimmed
 *
 * Mirrors [com.example.wags.ui.breathing.BreathingPacerCircle] (resonance
 * breathing): the circle always expands to the full outer radius regardless of
 * the configured breath depth. The target lung fullness is communicated via
 * the "to X%" label inside the circle instead of by scaling the animation.
 *
 * @param phase Current breathing phase from the pacer engine.
 * @param radius Normalised radius 0.0–1.0 from [com.example.wags.domain.usecase.breathing.EucapnicPacerEngine.getPacerRadius].
 * @param breathDepthPercent Target breath depth (15–50). Shown as the lung
 *                           fullness percent the user should inhale to.
 * @param modifier Layout modifier.
 * @param size Overall gauge diameter.
 * @param showLabel Whether to show the phase label and depth percent inside the gauge.
 * @param useColors When true, use coloured inhale/exhale hues (same palette as
 *                  resonance breathing) instead of the monochrome greys.
 * @param onPhaseTransition Optional callback fired exactly once per phase change.
 */
@Composable
fun EucapnicPacerGauge(
    phase: EucapnicPhase,
    radius: Float,
    breathDepthPercent: Int,
    modifier: Modifier = Modifier,
    size: Dp = 280.dp,
    showLabel: Boolean = true,
    useColors: Boolean = false,
    onPhaseTransition: ((EucapnicPhase) -> Unit)? = null
) {
    // Fire callback exactly once per phase change
    var lastPhase by remember { mutableStateOf(phase) }
    LaunchedEffect(phase) {
        if (phase != lastPhase) {
            lastPhase = phase
            onPhaseTransition?.invoke(phase)
        }
    }

    // Radius 0.0 = inner circle gone, 1.0 = fills the outer circle.
    // No depth scaling — the circle always expands fully, like resonance breathing.
    val clampedRadius = radius.coerceIn(0f, 1f)

    // Colour logic per phase — coloured mode matches resonance breathing
    val isPause = phase.isPause()
    val inhaleColor = if (useColors) PacerInhaleColor else PacerInhale
    val exhaleColor = if (useColors) PacerExhaleColor else PacerExhale
    val baseColor = when (phase) {
        EucapnicPhase.INHALE       -> inhaleColor
        EucapnicPhase.TOP_PAUSE    -> inhaleColor.copy(alpha = 0.70f)
        EucapnicPhase.EXHALE       -> exhaleColor
        EucapnicPhase.BOTTOM_PAUSE -> exhaleColor.copy(alpha = 0.35f)
    }
    val color by animateColorAsState(
        targetValue = baseColor,
        animationSpec = tween(durationMillis = 200),
        label = "eucapnic_gauge_color"
    )

    // Label
    val label = when (phase) {
        EucapnicPhase.INHALE       -> "INHALE"
        EucapnicPhase.TOP_PAUSE    -> "HOLD"
        EucapnicPhase.EXHALE       -> "EXHALE"
        EucapnicPhase.BOTTOM_PAUSE -> "PAUSE"
    }

    // Target lung fullness the user should inhale to (e.g. "to 35%")
    val depthLabel = "to $breathDepthPercent%"

    // Text colour: contrasting when circle is large, phase-coloured when small
    val targetTextColor = when {
        clampedRadius > 0.40f && !isPause -> BackgroundDark
        clampedRadius > 0.40f && isPause  -> TextPrimary
        else                              -> if (phase == EucapnicPhase.INHALE || phase == EucapnicPhase.TOP_PAUSE) inhaleColor else exhaleColor
    }
    val animatedTextColor by animateColorAsState(
        targetValue = targetTextColor,
        animationSpec = tween(durationMillis = 300),
        label = "eucapnic_gauge_text_color"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.size(size)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val outerRadius = this.size.minDimension / 2f

            // Outer track — always visible, very dim
            drawCircle(
                color = color.copy(alpha = 0.10f),
                radius = outerRadius
            )

            // Inner breathing circle — always expands to the full outer radius
            if (clampedRadius > 0.001f) {
                drawCircle(
                    color = color,
                    radius = outerRadius * clampedRadius
                )
            }
        }

        if (showLabel) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = animatedTextColor
                )
                Text(
                    text = depthLabel,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.Medium
                    ),
                    color = animatedTextColor.copy(alpha = 0.85f)
                )
            }
        }
    }
}
