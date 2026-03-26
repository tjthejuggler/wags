package com.example.wags.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
