package com.example.wags.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle

/** Modifier that strips colour saturation from any composable (emojis, images, etc.). */
fun Modifier.grayscale(): Modifier {
    val matrix = ColorMatrix().apply { setToSaturation(0f) }
    return graphicsLayer { colorFilter = ColorFilter.colorMatrix(matrix) }
}

/**
 * Dull tier colour for a trophy group, keyed by trophy count (1–6):
 *
 *   1 → orange   2 → green   3 → blue
 *   4 → pink     5 → yellow  6 → white
 */
fun trophyTierColor(count: Int): Color = when (count) {
    1    -> Color(0xFFB45A52)   // dull red
    2    -> Color(0xFFC08048)   // dull orange
    3    -> Color(0xFF7E9E6E)   // dull green
    4    -> Color(0xFF6E88B4)   // dull blue
    5    -> Color(0xFFB4829E)   // dull pink
    else -> Color(0xFFBFB06E)   // dull yellow
}

/**
 * Modifier that recolours any composable (trophy emojis, images, etc.) with
 * the dull tier colour for [count] trophies. Mostly desaturates the source
 * (leaving a hint of the original shading) and then tints the result toward
 * the tier colour — dull/greyed-out versions of the tier colours rather than
 * a flat tint.
 */
fun Modifier.trophyTint(count: Int): Modifier {
    val target = trophyTierColor(count)

    // How much of the tier colour to apply (0 = untouched, 1 = full tint).
    val strength = 0.85f

    // Convert the tier colour into per-channel multipliers normalised so the
    // average multiplier is 1 (preserves overall brightness while shifting
    // hue) — this keeps the tiers clearly distinct instead of all muddy.
    val avg = (target.red + target.green + target.blue) / 3f
    fun channel(c: Float) = (1f + ((c / avg) - 1f) * strength).coerceAtLeast(0f)

    // Build the final matrix directly: each output channel is the luminance
    // (rec.709 weights) scaled by the tier's channel multiplier. Constructing
    // it by hand avoids any ambiguity about ColorMatrix multiplication order.
    val mr = channel(target.red)
    val mg = channel(target.green)
    val mb = channel(target.blue)
    val matrix = ColorMatrix(
        floatArrayOf(
            0.2126f * mr, 0.7152f * mr, 0.0722f * mr, 0f, 0f,
            0.2126f * mg, 0.7152f * mg, 0.0722f * mg, 0f, 0f,
            0.2126f * mb, 0.7152f * mb, 0.0722f * mb, 0f, 0f,
            0f,           0f,           0f,           1f, 0f
        )
    )
    return graphicsLayer { colorFilter = ColorFilter.colorMatrix(matrix) }
}

/**
 * Renders an emoji (or any text containing emojis) with a zero-saturation
 * ColorMatrix applied, converting all colour pixels to greyscale.
 * Works on all API levels supported by the app (minSdk 26+).
 */
@Composable
fun GrayscaleEmoji(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
) {
    Text(
        text = text,
        style = style,
        modifier = modifier.grayscale()
    )
}
