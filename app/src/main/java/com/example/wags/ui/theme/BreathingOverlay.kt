package com.example.wags.ui.theme

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Full-screen scrim whose opacity breathes in the slow-rhythmic-breathing
 * rhythm (5 s dim + 5 s brighten = 10 s cycle ≈ 6 breaths per minute).
 *
 * Placed on top of the whole app UI, it makes ALL content — text, borders,
 * cards — pulse together by the same relative amount and in the same rhythm,
 * while preserving the brightness differences between elements (everything
 * is dimmed proportionally: bright text stays brighter than dim text).
 *
 * Implementation notes:
 *  - Draw-phase only: the animated alpha is read inside [Modifier.graphicsLayer],
 *    so nothing recomposes per frame — each animation step is just a cheap
 *    re-draw of this one node.
 *  - The Box has no pointer-input modifiers, so touches pass straight through
 *    to the UI underneath.
 */
@Composable
fun BreathingOverlay(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "breathingPulse")
    // 0f = fully bright; 0.35f = deepest "exhale" dim (~35% darker — clearly
    // perceptible on a full screen without feeling heavy).
    val dim by transition.animateFloat(
        initialValue = 0f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 5000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathingDim"
    )
    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer { alpha = dim }
            .background(Color.Black)
    )
}
