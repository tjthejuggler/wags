package com.example.wags.ui.common

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import android.view.WindowManager

/**
 * Full-screen, fully black overlay used during active sessions (e.g. meditation /
 * NSDR) as an alternative to turning the screen off.
 *
 * - Renders a pure-black layer over the entire screen (including dialogs/scaffolds
 *   when placed last in the composition).
 * - Dims the window backlight to the minimum while active, and restores the
 *   previous brightness on dismissal — saving battery just like a locked screen.
 * - The screen itself stays ON (FLAG_KEEP_SCREEN_ON is managed separately by
 *   [KeepScreenOn]), so the app keeps running and long sessions are not killed.
 * - Any tap anywhere dismisses the overlay and restores the normal UI.
 */
@Composable
fun BlackScreenOverlay(
    visible: Boolean,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    // Dim the backlight to minimum while the overlay is showing.
    DisposableEffect(visible) {
        val window = (context as? ComponentActivity)?.window
        val originalBrightness = window?.attributes?.screenBrightness
        if (visible) {
            window?.attributes = window?.attributes?.apply {
                screenBrightness = 0.01f
            }
        }
        onDispose {
            if (visible && originalBrightness != null && originalBrightness >= 0f) {
                window?.attributes = window?.attributes?.apply {
                    screenBrightness = originalBrightness
                }
            } else if (visible) {
                // -1 (BRIGHTNESS_OVERRIDE_NONE) means system default — restore that
                window?.attributes = window?.attributes?.apply {
                    screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                }
            }
        }
    }

    if (visible) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onDismiss() }
        )
    }
}
