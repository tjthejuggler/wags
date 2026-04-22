package com.example.wags.ui.common.pip

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Reusable wrapper that any session screen drops around its content to gain PiP support.
 *
 * When [pipEnabled] is true and the user presses Home / switches apps, the Activity
 * automatically shrinks into the OS PiP window and [pipContent] is rendered inside it.
 * When the user returns to the app, [fullContent] is rendered again.
 *
 * The host also keeps [PipController] in sync:
 * - Registers/unregisters PiP eligibility via [PipController.setPipEligible].
 * - Pushes the current [actions] list to [PipController.setActions] whenever it changes.
 *
 * @param pipEnabled  True when PiP should be available (session active OR result showing).
 * @param actions     Buttons to show in the OS PiP overlay (max 3). Update reactively.
 * @param pipContent  Compact layout rendered inside the PiP window.
 * @param fullContent Normal full-screen layout.
 */
@Composable
fun PipSessionHost(
    pipEnabled: Boolean,
    actions: List<PipAction> = emptyList(),
    pipContent: @Composable () -> Unit,
    fullContent: @Composable () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity

    // Keep eligibility in sync with pipEnabled.
    // We pass the Activity so PipController can immediately call setPictureInPictureParams()
    // with setAutoEnterEnabled(true/false) — required for reliable PiP on API 31+.
    DisposableEffect(pipEnabled) {
        if (activity != null) {
            PipController.setPipEligible(activity, pipEnabled)
        }
        onDispose {
            if (activity != null) {
                PipController.setPipEligible(activity, false)
            }
        }
    }

    // Push action list to PipController whenever it changes
    DisposableEffect(actions) {
        if (activity != null) {
            PipController.setActions(activity, actions)
        }
        onDispose { }
    }

    val isInPip by PipController.isInPipMode.collectAsStateWithLifecycle()

    if (isInPip) {
        pipContent()
    } else {
        fullContent()
    }
}
