package com.example.wags.ui.apnea

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import com.example.wags.ui.theme.EcgCyan
import com.example.wags.ui.theme.ReadinessGreen
import kotlin.math.sin
import kotlin.random.Random

// ─────────────────────────────────────────────────────────────────────────────
// Confetti particle system — lightweight Canvas-based celebration overlay
// ─────────────────────────────────────────────────────────────────────────────

private data class ConfettiParticle(
    val x: Float,           // normalised 0..1 horizontal start position
    val delay: Float,       // normalised 0..1 delay before appearing
    val speed: Float,       // fall speed multiplier (0.6..1.4)
    val wobbleAmp: Float,   // horizontal wobble amplitude
    val wobbleFreq: Float,  // horizontal wobble frequency
    val rotation: Float,    // initial rotation degrees
    val rotationSpeed: Float, // degrees per normalised-time unit
    val color: Color,
    val width: Float,       // particle width in dp-ish units
    val height: Float       // particle height in dp-ish units
)

private val confettiColors = listOf(
    EcgCyan,
    ReadinessGreen,
    Color(0xFFFFD700),  // gold
    Color(0xFFFF6B6B),  // soft coral
    Color(0xFF7C4DFF),  // soft purple
    Color(0xFFFFFFFF),  // white
)

private fun generateParticles(count: Int): List<ConfettiParticle> {
    val rng = Random(System.nanoTime())
    return List(count) {
        ConfettiParticle(
            x = rng.nextFloat(),
            delay = rng.nextFloat() * 0.3f,          // stagger over first 30% of animation
            speed = 0.6f + rng.nextFloat() * 0.8f,
            wobbleAmp = 0.01f + rng.nextFloat() * 0.03f,
            wobbleFreq = 2f + rng.nextFloat() * 4f,
            rotation = rng.nextFloat() * 360f,
            rotationSpeed = 90f + rng.nextFloat() * 270f,
            color = confettiColors[rng.nextInt(confettiColors.size)],
            width = 4f + rng.nextFloat() * 5f,
            height = 8f + rng.nextFloat() * 10f
        )
    }
}

/**
 * A full-screen confetti overlay that animates falling particles once.
 *
 * Place this inside a `Box(Modifier.fillMaxSize())` layered on top of
 * (or behind) the content you want to celebrate.
 *
 * @param particleCount  Number of confetti pieces (keep ≤ 60 for subtlety).
 * @param durationMs     Total animation duration in milliseconds.
 */
@Composable
fun ConfettiOverlay(
    modifier: Modifier = Modifier,
    particleCount: Int = 40,
    durationMs: Int = 3_000
) {
    val particles = remember { generateParticles(particleCount) }
    val progress = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = durationMs, easing = LinearEasing)
        )
    }

    val t = progress.value

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val density = 2.5f  // rough dp→px multiplier for particle sizing

        for (p in particles) {
            // Effective time for this particle (accounting for stagger delay)
            val effectiveT = ((t - p.delay) / (1f - p.delay)).coerceIn(0f, 1f)
            if (effectiveT <= 0f) continue

            // Vertical position: start above the top, fall past the bottom
            val yPos = -20f + (h + 40f) * effectiveT * p.speed

            // Horizontal wobble
            val xOffset = sin(effectiveT * p.wobbleFreq * Math.PI.toFloat() * 2f) * p.wobbleAmp * w
            val xPos = p.x * w + xOffset

            // Fade out in the last 20% of the particle's life
            val alpha = if (effectiveT > 0.8f) {
                ((1f - effectiveT) / 0.2f).coerceIn(0f, 1f)
            } else {
                1f
            }

            val rotation = p.rotation + p.rotationSpeed * effectiveT
            val pw = p.width * density
            val ph = p.height * density

            rotate(degrees = rotation, pivot = Offset(xPos, yPos)) {
                drawRect(
                    color = p.color.copy(alpha = alpha * 0.85f),
                    topLeft = Offset(xPos - pw / 2f, yPos - ph / 2f),
                    size = Size(pw, ph)
                )
            }
        }
    }
}
