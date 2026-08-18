package com.example.wags.ui.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import kotlin.math.PI
import kotlin.math.cos

/**
 * Full-screen scrim whose opacity breathes in the slow-rhythmic-breathing
 * rhythm (10 s cycle ≈ 6 breaths per minute).
 *
 * In [wave] mode the dimming travels top → bottom: each horizontal band of
 * the screen starts (and peaks) its pulse slightly after the band above it,
 * so stacked cards / text sections pulse in a cascading wave — the top card
 * leads, the bottom one follows ~3 s later. With [wave] false the whole
 * screen pulses uniformly in phase.
 *
 * In both modes every band dims by the same peak amount, so relative
 * brightness differences between elements are preserved.
 *
 * Implementation notes:
 *  - Draw-phase only: the animated phase is read inside [Modifier.drawBehind],
 *    so nothing recomposes per frame — each animation step is a cheap
 *    re-draw of this one node (a 48-stop vertical gradient).
 *  - The Box has no pointer-input modifiers, so touches pass straight through
 *    to the UI underneath.
 */
@Composable
fun BreathingOverlay(
    wave: Boolean = false,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "breathingPulse")
    // Phase 0..1 over one full breath (dim + brighten); wraps around.
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(10_000, easing = LinearEasing)),
        label = "breathingPhase"
    )
    Box(
        modifier = modifier
            .fillMaxSize()
            .drawBehind {
                // Peak dim at the deepest "exhale" (~35% darker).
                val maxDim = 0.35f
                // Fraction of the cycle by which the bottom of the screen
                // lags the top (0 = uniform pulse, 0.3 = ~3 s top→bottom lag).
                val spread = if (wave) 0.3f else 0f
                val steps = 48
                val stops = ArrayList<Pair<Float, Color>>(steps + 1)
                for (i in 0..steps) {
                    val y = i.toFloat() / steps
                    // Positive modulo into 0..1, then a smooth inhale/exhale
                    // shape: 0 → 1 → 0 across the cycle (cosine ramp).
                    val p = (((phase - y * spread) % 1f) + 1f) % 1f
                    val shape = 0.5f - 0.5f * cos(2f * PI.toFloat() * p)
                    stops.add(y to Color.Black.copy(alpha = maxDim * shape))
                }
                drawRect(brush = Brush.verticalGradient(*stops.toTypedArray()))
            }
    )
}
