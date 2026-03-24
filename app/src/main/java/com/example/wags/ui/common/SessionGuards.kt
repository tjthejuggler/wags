package com.example.wags.ui.common

import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalView

/**
 * Intercepts the system back gesture / button while [enabled] is true and shows
 * a confirmation dialog before navigating away.
 *
 * When [enabled] is false the handler is inactive and the default back behaviour
 * applies (no dialog shown).
 *
 * @param enabled   Whether the guard is active (typically true while a session is running).
 * @param onConfirm Called when the user confirms they want to leave (e.g. popBackStack).
 */
@Composable
fun SessionBackHandler(
    enabled: Boolean,
    onConfirm: () -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }

    BackHandler(enabled = enabled) {
        showDialog = true
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Discard session?") },
            text = { Text("Going back will discard the current session. Are you sure?") },
            confirmButton = {
                TextButton(onClick = {
                    showDialog = false
                    onConfirm()
                }) {
                    Text("Discard")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

/**
 * Keeps the screen on (prevents auto-dim / auto-off) while [enabled] is true.
 *
 * Uses [WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON] on the current window.
 * The flag is automatically cleared when [enabled] becomes false or when the
 * composable leaves the composition.
 */
@Composable
fun KeepScreenOn(enabled: Boolean) {
    val currentView = LocalView.current
    DisposableEffect(enabled) {
        if (enabled) {
            currentView.keepScreenOn = true
        }
        onDispose {
            currentView.keepScreenOn = false
        }
    }
}
