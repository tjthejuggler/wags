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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.wags.domain.model.EucapnicPhase
import com.example.wags.ui.theme.BackgroundDark
import com.example.wags.ui.theme.PacerExhale
import com.example.wags.ui.theme.PacerInhale
import com.example.wags.ui.theme.TextPrimary
import com.example.wags.ui.theme.TextSecondary

/**
 * Visual breathing gauge for Eucapnic Diaphragmatic preparation.
 *
 * Renders an expanding/contracting circle driven by the pacer engine's radius:
 * - **INHALE**: circle expands from minimum to depth-scaled maximum
 * - **TOP_PAUSE**: circle holds at full expansion
 * - **EXHALE**: circle contracts from maximum back to minimum
 * - **BOTTOM_PAUSE**: circle holds at minimum, dimmed
 *
 * The maximum radius is scaled by [breathDepthPercent] (15–50 %) so the user
 * sees a visual representation of how deep each breath should be.
 *
 * Follows the same Canvas-based pattern as [BreathingPacerCircle] but adds
 * 4-phase support and depth scaling.
 *
 * @param phase Current breathing phase from the pacer engine.
 * @param radius Normalised radius 0.0–1.0 from [EucapnicPacerEngine.getPacerRadius].
 * @param breathDepthPercent Target breath depth (15–50). Scales the maximum radius.
 * @param modifier Layout modifier.
 * @param size Overall gauge diameter.
 * @param showLabel Whether to show the phase label inside the gauge.
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

    val clampedRadius = radius.coerceIn(0f, 1f)

    // Depth scaling: map 15–50 % → 0.30–1.00 of the outer radius
    val depthFraction = (breathDepthPercent.coerceIn(15, 50) / 50f)
    val maxRadiusScale = 0.30f + 0.70f * depthFraction

    // Effective radius: scale the engine radius by depth
    val effectiveRadius = clampedRadius * maxRadiusScale

    // Colour logic per phase
    val isPause = phase.isPause()
    val baseColor = when (phase) {
        EucapnicPhase.INHALE       -> PacerInhale
        EucapnicPhase.TOP_PAUSE    -> PacerInhale.copy(alpha = 0.70f)
        EucapnicPhase.EXHALE       -> PacerExhale
        EucapnicPhase.BOTTOM_PAUSE -> PacerExhale.copy(alpha = 0.35f)
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

    // Text colour: contrasting when circle is large, phase-coloured when small
    val targetTextColor = when {
        effectiveRadius > 0.40f && !isPause -> BackgroundDark
        effectiveRadius > 0.40f && isPause  -> TextPrimary
        else                                -> if (phase == EucapnicPhase.INHALE || phase == EucapnicPhase.TOP_PAUSE) PacerInhale else PacerExhale
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

            // Depth guide ring — shows the target maximum for this depth setting
            if (maxRadiusScale < 1f) {
                drawCircle(
                    color = color.copy(alpha = 0.06f),
                    radius = outerRadius * maxRadiusScale
                )
            }

            // Inner breathing circle
            if (effectiveRadius > 0.001f) {
                drawCircle(
                    color = color,
                    radius = outerRadius * effectiveRadius
                )
            }
        }

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
