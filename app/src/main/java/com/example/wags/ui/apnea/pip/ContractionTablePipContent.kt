package com.example.wags.ui.apnea.pip

import android.app.Activity
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.wags.R
import com.example.wags.domain.model.trophyEmojis
import com.example.wags.domain.usecase.apnea.ContractionTableMode
import com.example.wags.domain.usecase.apnea.ContractionTablePhase
import com.example.wags.ui.apnea.ContractionTableViewModel
import com.example.wags.ui.common.pip.*

/**
 * PiP overlay content for the Contraction Table active screen.
 *
 * The PiP window is read-only — all interaction happens via OS RemoteAction
 * buttons. Compose touch events are blocked by the OS in PiP mode.
 *
 * States:
 *  - BREATHE  → rest countdown + OS "Stop" button
 *  - CRUISE   → count-up timer + OS "1st Contraction" + "Stop" buttons
 *  - STRUGGLE → count-up timer + OS "Contraction" (reuses the first-contraction
 *               action id; the ViewModel dispatches by phase) + "Stop"
 *  - COMPLETE → result display + OS "Again" button
 */
@Composable
fun ContractionTablePipContent(
    viewModel: ContractionTableViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as? Activity
    val session = state.sessionState
    val phase = session.phase

    // Build OS overlay actions reactively
    val pipActions = remember(phase) {
        when (phase) {
            ContractionTablePhase.COMPLETE -> listOf(
                PipAction(PipActionIds.AGAIN, "Again", R.drawable.ic_pip_repeat)
            )
            ContractionTablePhase.CRUISE -> listOf(
                PipAction(PipActionIds.FIRST_CONTRACTION, "1st Contraction", R.drawable.ic_pip_contraction),
                PipAction(PipActionIds.STOP, "Stop", R.drawable.ic_pip_stop)
            )
            ContractionTablePhase.STRUGGLE -> listOf(
                PipAction(PipActionIds.FIRST_CONTRACTION, "Contraction", R.drawable.ic_pip_contraction),
                PipAction(PipActionIds.STOP, "Stop", R.drawable.ic_pip_stop)
            )
            else -> listOf(
                PipAction(PipActionIds.STOP, "Stop", R.drawable.ic_pip_stop)
            )
        }
    }

    LaunchedEffect(pipActions) {
        if (activity != null) PipController.setActions(activity, pipActions)
    }

    LaunchedEffect(Unit) {
        PipController.actionFlow.collect { actionId ->
            when (actionId) {
                PipActionIds.FIRST_CONTRACTION -> {
                    // Dispatch by phase: first contraction during CRUISE,
                    // subsequent contractions during STRUGGLE.
                    if (session.phase == ContractionTablePhase.CRUISE) viewModel.logFirstContraction()
                    else viewModel.logContraction()
                }
                PipActionIds.STOP              -> viewModel.stopSession()
                PipActionIds.AGAIN             -> viewModel.restartSameSession()
            }
        }
    }

    // Read-only display — no Compose buttons (touch is blocked by OS in PiP mode)
    PipRoot {
        when (phase) {
            ContractionTablePhase.COMPLETE -> {
                val rounds = session.roundResults
                val completedRounds = rounds.count { it.completed }
                val headline = if (state.mode == ContractionTableMode.TILL_CONTRACTION) {
                    formatPipMs(session.longestHoldMs)
                } else {
                    formatPipMs(session.totalHoldTimeMs)
                }
                val modeName = if (state.mode == ContractionTableMode.TILL_CONTRACTION) "Till Contraction" else "Contraction Count"
                PipResultCard(
                    headline = headline,
                    subline = "$modeName · $completedRounds rounds",
                    trophies = state.newPersonalBest?.category?.trophyEmojis() ?: ""
                )
            }
            ContractionTablePhase.CRUISE -> {
                PipTimerText(formatPipMs(session.timerMs))
                PipLabel("CRUISE · Round ${session.currentRound}")
            }
            ContractionTablePhase.STRUGGLE -> {
                PipTimerText(formatPipMs(session.timerMs))
                PipLabel("STRUGGLE · ${session.contractionsInHold}/${session.contractionTarget}c")
            }
            ContractionTablePhase.BREATHE -> {
                PipTimerText(formatPipMs(session.timerMs))
                PipLabel("BREATHE · Round ${session.currentRound}")
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
