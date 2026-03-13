package com.example.wags.ui.apnea

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.example.wags.ui.common.LiveSensorActions
import com.example.wags.ui.theme.*
import kotlinx.coroutines.delay

/**
 * Full-screen breath-hold active screen.
 *
 * Layout (top → bottom):
 *  • TopAppBar  — back arrow + live HR/SpO₂ sensor strip
 *  • Timer      — large elapsed-time display (shown when showTimer is true)
 *  • Large button area:
 *      – Before start: single "START" button (nearly full-screen)
 *      – After start, before first contraction: "First Contraction" + "Stop" side-by-side
 *      – After first contraction tapped: single "Stop" button
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FreeHoldActiveScreen(
    navController: NavController,
    viewModel: ApneaViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val deviceId = "PLACEHOLDER_H10_ID"

    // Navigate back automatically when the hold is stopped
    LaunchedEffect(state.freeHoldActive) {
        // If we arrive here and the hold is already inactive (e.g. user pressed back
        // then re-entered), just pop back so we don't get stuck.
        // The real "stop → pop" is handled by the Stop button calling stopFreeHold()
        // and then navController.popBackStack().
    }

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = { Text("Breath Hold", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = {
                        // Cancel (not save) if a hold is in progress
                        if (state.freeHoldActive) viewModel.cancelFreeHold()
                        navController.popBackStack()
                    }) {
                        Text("←", style = MaterialTheme.typography.headlineMedium, color = EcgCyan)
                    }
                },
                actions = {
                    LiveSensorActions(liveHr = state.liveHr, liveSpO2 = state.liveSpO2)
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark)
            )
        }
    ) { padding ->
        FreeHoldActiveContent(
            freeHoldActive = state.freeHoldActive,
            showTimer = state.showTimer,
            firstContractionMs = state.freeHoldFirstContractionMs,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            onStart = {
                viewModel.startFreeHold(deviceId)
            },
            onFirstContraction = {
                viewModel.recordFreeHoldFirstContraction()
            },
            onStop = {
                viewModel.stopFreeHold()
                navController.popBackStack()
            }
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Content
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun FreeHoldActiveContent(
    freeHoldActive: Boolean,
    showTimer: Boolean,
    firstContractionMs: Long?,
    modifier: Modifier = Modifier,
    onStart: () -> Unit,
    onFirstContraction: () -> Unit,
    onStop: () -> Unit
) {
    // Local elapsed-time ticker — runs only while the hold is active
    var elapsedMs by remember { mutableLongStateOf(0L) }
    val holdStartWallClock = remember { mutableLongStateOf(0L) }

    LaunchedEffect(freeHoldActive) {
        if (freeHoldActive) {
            holdStartWallClock.longValue = System.currentTimeMillis()
            while (true) {
                elapsedMs = System.currentTimeMillis() - holdStartWallClock.longValue
                delay(50L)
            }
        } else {
            elapsedMs = 0L
        }
    }

    Column(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {

        // ── Timer display ─────────────────────────────────────────────────────
        if (freeHoldActive && showTimer) {
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = formatElapsedMs(elapsedMs),
                style = MaterialTheme.typography.displayLarge,
                color = ApneaHold,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            firstContractionMs?.let { fcMs ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "First contraction: ${formatElapsedMs(fcMs)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ReadinessOrange,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        } else if (freeHoldActive) {
            // Timer hidden — show "HOLD" label so the user knows it's running
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "HOLD",
                style = MaterialTheme.typography.displayLarge,
                color = ApneaHold,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            firstContractionMs?.let { fcMs ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "First contraction: ${formatElapsedMs(fcMs)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ReadinessOrange,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        } else {
            Spacer(modifier = Modifier.height(24.dp))
        }

        // ── Main button area (fills remaining space) ──────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center
        ) {
            when {
                // ── Not yet started ───────────────────────────────────────────
                !freeHoldActive -> {
                    Button(
                        onClick = onStart,
                        modifier = Modifier
                            .fillMaxWidth(0.92f)
                            .fillMaxHeight(0.85f),
                        shape = MaterialTheme.shapes.large,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ButtonSuccess,
                            contentColor = Color.White
                        )
                    ) {
                        Text(
                            text = "START",
                            style = MaterialTheme.typography.displayMedium,
                            fontWeight = FontWeight.ExtraBold,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                // ── Active, first contraction not yet recorded ────────────────
                freeHoldActive && firstContractionMs == null -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(0.85f),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // First Contraction button
                        Button(
                            onClick = onFirstContraction,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            shape = MaterialTheme.shapes.large,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ReadinessOrange,
                                contentColor = Color.White
                            )
                        ) {
                            Text(
                                text = "First\nContraction",
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                        }

                        // Stop button
                        Button(
                            onClick = onStop,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            shape = MaterialTheme.shapes.large,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ButtonDanger,
                                contentColor = Color.White
                            )
                        ) {
                            Text(
                                text = "Stop",
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }

                // ── Active, first contraction already recorded ────────────────
                else -> {
                    Button(
                        onClick = onStop,
                        modifier = Modifier
                            .fillMaxWidth(0.92f)
                            .fillMaxHeight(0.85f),
                        shape = MaterialTheme.shapes.large,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ButtonDanger,
                            contentColor = Color.White
                        )
                    ) {
                        Text(
                            text = "Stop",
                            style = MaterialTheme.typography.displayMedium,
                            fontWeight = FontWeight.ExtraBold,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────────

private fun formatElapsedMs(ms: Long): String {
    val totalSeconds = ms / 1000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    val centis = (ms % 1000L) / 10L
    return if (minutes > 0)
        "${minutes}m ${seconds}s"
    else
        "${seconds}.${centis.toString().padStart(2, '0')}s"
}
