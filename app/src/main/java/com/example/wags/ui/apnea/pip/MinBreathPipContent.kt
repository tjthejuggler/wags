package com.example.wags.ui.apnea.pip

import android.app.Activity
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.wags.R
import com.example.wags.domain.model.trophyEmojis
import com.example.wags.domain.usecase.apnea.MinBreathPhase
import com.example.wags.ui.apnea.MinBreathViewModel
import com.example.wags.ui.common.pip.*

/**
 * PiP overlay content for the Min Breath active screen.
 *
 * The PiP window is read-only — all interaction happens via OS RemoteAction
 * buttons (the icon buttons in the system overlay). Compose touch events are
 * blocked by the OS in PiP mode.
 *
 * States:
 *  - HOLD, no contraction → timer + OS "1st Contraction" + "Breath" buttons
 *  - HOLD, contraction logged → timer + contraction time + OS "Breath" button
 *  - BREATHING → timer + OS "Hold" + "Stop" buttons
 *  - COMPLETE → result display + OS "Again" button
 */
@Composable
fun MinBreathPipContent(
    viewModel: MinBreathViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as? Activity
    val session = state.sessionState
    val phase = session.phase

    // Build OS overlay actions reactively
    val pipActions = remember(phase, session.currentHoldContractionMs) {
        when (phase) {
            MinBreathPhase.COMPLETE -> listOf(
                PipAction(PipActionIds.AGAIN, "Again", R.drawable.ic_pip_repeat)
            )
            MinBreathPhase.HOLD -> if (session.currentHoldContractionMs == null) listOf(
                PipAction(PipActionIds.FIRST_CONTRACTION, "1st Contraction", R.drawable.ic_pip_contraction),
                PipAction(PipActionIds.BREATH, "Breath", R.drawable.ic_pip_breath)
            ) else listOf(
                PipAction(PipActionIds.BREATH, "Breath", R.drawable.ic_pip_breath)
            )
            MinBreathPhase.BREATHING -> listOf(
                PipAction(PipActionIds.HOLD, "Hold", R.drawable.ic_pip_play),
                PipAction(PipActionIds.STOP, "Stop", R.drawable.ic_pip_stop)
            )
            else -> emptyList()
        }
    }

    LaunchedEffect(pipActions) {
        if (activity != null) PipController.setActions(activity, pipActions)
    }

    LaunchedEffect(Unit) {
        PipController.actionFlow.collect { actionId ->
            when (actionId) {
                PipActionIds.FIRST_CONTRACTION -> viewModel.markContraction()
                PipActionIds.BREATH            -> viewModel.switchToBreathing()
                PipActionIds.HOLD              -> viewModel.switchToHolding()
                PipActionIds.STOP              -> viewModel.stopSession()
                PipActionIds.AGAIN             -> viewModel.restartSameSession()
            }
        }
    }

    // Read-only display — no Compose buttons (touch is blocked by OS in PiP mode)
    PipRoot {
        when (phase) {
            MinBreathPhase.COMPLETE -> {
                val holds = session.holdResults
                val longestMs = holds.maxOfOrNull { it.holdDurationMs } ?: 0L
                PipResultCard(
                    headline = formatPipMs(longestMs),
                    subline = "Min Breath · ${holds.size} holds",
                    trophies = state.newPersonalBest?.category?.trophyEmojis() ?: ""
                )
            }
            MinBreathPhase.HOLD -> {
                PipTimerText(formatPipMs(session.currentPhaseElapsedMs))
                PipLabel("HOLD #${session.currentHoldNumber} · ${formatPipMs(session.sessionRemainingMs)} left")
                if (session.currentHoldContractionMs != null) {
                    PipLabel("⚡ ${formatPipMs(session.currentHoldContractionMs!!)}")
                }
            }
            MinBreathPhase.BREATHING -> {
                PipTimerText(formatPipMs(session.currentPhaseElapsedMs))
                PipLabel("BREATHING · ${formatPipMs(session.sessionRemainingMs)} left")
            }
            else -> {
                PipLabel("Starting…")
            }
        }
    }
}

private fun formatPipMs(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val m = totalSec / 60
    val s = totalSec % 60
    return "$m:${s.toString().padStart(2, '0')}"
}
