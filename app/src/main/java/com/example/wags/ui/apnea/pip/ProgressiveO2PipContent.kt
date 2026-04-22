package com.example.wags.ui.apnea.pip

import android.app.Activity
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.wags.R
import com.example.wags.domain.model.trophyEmojis
import com.example.wags.domain.usecase.apnea.ProgressiveO2Phase
import com.example.wags.ui.apnea.ProgressiveO2ViewModel
import com.example.wags.ui.common.pip.*

/**
 * PiP overlay content for the Progressive O₂ active screen.
 *
 * The PiP window is read-only — all interaction happens via OS RemoteAction
 * buttons (the icon buttons in the system overlay). Compose touch events are
 * blocked by the OS in PiP mode.
 *
 * States:
 *  - IDLE / HOLD (no contraction) → timer + OS "1st Contraction" + "Stop" buttons
 *  - HOLD (contraction logged)    → timer + OS "Stop" button
 *  - BREATHING                    → timer + OS "Stop" button
 *  - COMPLETE                     → result display + OS "Again" button
 */
@Composable
fun ProgressiveO2PipContent(
    viewModel: ProgressiveO2ViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as? Activity
    val session = state.sessionState
    val phase = session.phase

    // Build OS overlay actions reactively
    val pipActions = remember(phase, session.firstContractionMs) {
        when (phase) {
            ProgressiveO2Phase.COMPLETE -> listOf(
                PipAction(PipActionIds.AGAIN, "Again", R.drawable.ic_pip_repeat)
            )
            ProgressiveO2Phase.HOLD -> if (session.firstContractionMs == null) listOf(
                PipAction(PipActionIds.FIRST_CONTRACTION, "1st Contraction", R.drawable.ic_pip_contraction),
                PipAction(PipActionIds.STOP, "Stop", R.drawable.ic_pip_stop)
            ) else listOf(
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
                PipActionIds.FIRST_CONTRACTION -> viewModel.logFirstContraction()
                PipActionIds.STOP              -> viewModel.stopSession()
                PipActionIds.AGAIN             -> viewModel.restartSameSession()
            }
        }
    }

    // Read-only display — no Compose buttons (touch is blocked by OS in PiP mode)
    PipRoot {
        when (phase) {
            ProgressiveO2Phase.COMPLETE -> {
                val rounds = session.roundResults
                val completedRounds = rounds.count { it.completed }
                val maxHoldMs = rounds.filter { it.completed }.maxOfOrNull { it.targetHoldMs } ?: 0L
                PipResultCard(
                    headline = formatPipMs(maxHoldMs),
                    subline = "Prog O₂ · $completedRounds rounds",
                    trophies = state.newPersonalBest?.category?.trophyEmojis() ?: ""
                )
            }
            ProgressiveO2Phase.HOLD -> {
                PipTimerText(formatPipMs(session.timerMs))
                PipLabel("HOLD · Round ${session.currentRound}")
                if (session.firstContractionMs != null) {
                    PipLabel("⚡ ${formatPipMs(session.firstContractionMs!!)}")
                }
            }
            ProgressiveO2Phase.BREATHING -> {
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
