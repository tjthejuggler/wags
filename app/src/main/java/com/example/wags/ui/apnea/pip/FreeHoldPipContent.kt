package com.example.wags.ui.apnea.pip

import android.app.Activity
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.wags.R
import com.example.wags.domain.model.trophyEmojis
import com.example.wags.ui.apnea.FreeHoldActiveViewModel
import com.example.wags.ui.common.pip.*
import kotlinx.coroutines.delay

/**
 * PiP overlay content for the Free Hold screen.
 *
 * The PiP window is read-only — all interaction happens via OS RemoteAction
 * buttons (the icon buttons in the system overlay). Compose touch events are
 * blocked by the OS in PiP mode.
 *
 * States:
 *  - Pre-start  → label "Ready" + OS Start button
 *  - Active, no contraction → timer + OS "1st Contraction" + "Stop" buttons
 *  - Active, contraction logged → timer + contraction time + OS "Stop" button
 *  - Result → time + trophy + OS "Again" button
 */
@Composable
fun FreeHoldPipContent(
    viewModel: FreeHoldActiveViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as? Activity

    // Live elapsed timer for the PiP window
    var elapsedMs by remember { mutableLongStateOf(0L) }
    val holdStartWall = remember { mutableLongStateOf(0L) }
    LaunchedEffect(state.freeHoldActive) {
        if (state.freeHoldActive) {
            holdStartWall.longValue = System.currentTimeMillis()
            while (true) {
                elapsedMs = System.currentTimeMillis() - holdStartWall.longValue
                delay(200L)
            }
        } else {
            elapsedMs = 0L
        }
    }

    // Build OS overlay actions reactively
    val pipActions = remember(state.freeHoldActive, state.freeHoldFirstContractionMs, state.pbCheckPending, state.newPersonalBest) {
        when {
            state.newPersonalBest != null || (state.pbCheckPending && !state.freeHoldActive) -> listOf(
                PipAction(PipActionIds.AGAIN, "Again", R.drawable.ic_pip_repeat)
            )
            !state.freeHoldActive -> listOf(
                PipAction(PipActionIds.START, "Start", R.drawable.ic_pip_play)
            )
            state.freeHoldFirstContractionMs == null -> listOf(
                PipAction(PipActionIds.FIRST_CONTRACTION, "1st Contraction", R.drawable.ic_pip_contraction),
                PipAction(PipActionIds.STOP, "Stop", R.drawable.ic_pip_stop)
            )
            else -> listOf(
                PipAction(PipActionIds.STOP, "Stop", R.drawable.ic_pip_stop)
            )
        }
    }

    // Push actions to OS overlay
    LaunchedEffect(pipActions) {
        if (activity != null) PipController.setActions(activity, pipActions)
    }

    // React to OS overlay button taps
    LaunchedEffect(Unit) {
        PipController.actionFlow.collect { actionId ->
            when (actionId) {
                PipActionIds.START             -> viewModel.startFreeHold()
                PipActionIds.FIRST_CONTRACTION -> viewModel.recordFreeHoldFirstContraction()
                PipActionIds.STOP              -> viewModel.stopFreeHold()
                PipActionIds.AGAIN             -> viewModel.restartSameSession()
            }
        }
    }

    // Read-only display — no Compose buttons (touch is blocked by OS in PiP mode)
    PipRoot {
        when {
            // ── Result state ──────────────────────────────────────────────
            state.newPersonalBest != null -> {
                val pb = state.newPersonalBest!!
                PipResultCard(
                    headline = formatPipMs(pb.durationMs),
                    subline = "New PB! ${pb.description}",
                    trophies = pb.category.trophyEmojis()
                )
            }
            !state.freeHoldActive && !state.pbCheckPending -> {
                // ── Pre-start ─────────────────────────────────────────────
                PipLabel("Free Hold")
                PipLabel("▶ tap Start")
            }
            state.pbCheckPending && !state.freeHoldActive -> {
                // ── Saving result ─────────────────────────────────────────
                PipLabel("Saving…")
            }
            state.freeHoldActive && state.freeHoldFirstContractionMs == null -> {
                // ── Active, no contraction yet ────────────────────────────
                PipTimerText(formatPipMs(elapsedMs))
                PipLabel("Holding…")
            }
            state.freeHoldActive -> {
                // ── Active, contraction logged ────────────────────────────
                PipTimerText(formatPipMs(elapsedMs))
                PipLabel("⚡ ${formatPipMs(state.freeHoldFirstContractionMs ?: 0L)}")
            }
            else -> {
                PipLabel("Free Hold")
            }
        }
    }
}

/** Format milliseconds as M:SS for the compact PiP timer. */
private fun formatPipMs(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val m = totalSec / 60
    val s = totalSec % 60
    return "$m:${s.toString().padStart(2, '0')}"
}
