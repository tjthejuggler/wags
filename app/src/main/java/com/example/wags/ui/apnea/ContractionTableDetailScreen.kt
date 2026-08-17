package com.example.wags.ui.apnea

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.example.wags.domain.usecase.apnea.ContractionTableMode
import com.example.wags.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ─────────────────────────────────────────────────────────────────────────────
// Contraction Table Session Detail Screen
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContractionTableDetailScreen(
    navController: NavController,
    viewModel: ContractionTableDetailViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (state.mode == ContractionTableMode.TILL_CONTRACTION) "Till Contraction"
                        else "Contraction Count"
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Text(
                            "←",
                            style = MaterialTheme.typography.headlineMedium,
                            color = TextSecondary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark)
            )
        }
    ) { padding ->
        when {
            state.isLoading -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }

            state.notFound -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text("Session not found", color = TextSecondary)
            }

            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // ── Header ─────────────────────────────────────────────────────
                state.session?.let { session ->
                    val dateFormat = remember { SimpleDateFormat("EEEE, MMM d yyyy · HH:mm", Locale.getDefault()) }
                    Text(
                        text = dateFormat.format(Date(session.timestamp)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                    Text(
                        text = if (state.mode == ContractionTableMode.TILL_CONTRACTION) {
                            "${state.roundsConfigured} rounds · rest ${state.restStartSec}s" +
                                    if (state.restEndSec != state.restStartSec) " → ${state.restEndSec}s" else ""
                        } else {
                            "${state.roundsConfigured} rounds · rest ${state.restStartSec}s" +
                                    if (state.restEndSec != state.restStartSec) " → ${state.restEndSec}s" else "" +
                                            " · ${state.contractionTarget} contractions"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }

                // ── Summary card ───────────────────────────────────────────────
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceVariant)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        DetailRow("Rounds completed", "${state.roundsCompleted} / ${state.totalRoundsAttempted}")
                        state.bestCruiseSec?.let { DetailRow("Best cruise", formatSec(it)) }
                        DetailRow("Longest hold", formatSec(state.longestHoldSec))
                        DetailRow("Total hold time", formatSec(state.totalHoldSec))
                        DetailRow("Session duration", formatSec(state.sessionDurationSec))
                        state.avgCruiseRatio?.let {
                            DetailRow("Avg cruise ratio", "${(it * 100).toInt()}%")
                        }
                        state.minHr?.let { DetailRow("HR", "${state.minHr}–${state.maxHr} bpm · avg ${state.avgHr}") }
                        state.lowestSpO2?.let { DetailRow("Lowest SpO₂", "$it%") }
                    }
                }

                // ── Cruise decay chart ─────────────────────────────────────────
                val cruiseRounds = state.roundResults.mapNotNull { r ->
                    r.cruiseSec?.let { r.roundNumber to it }
                }
                if (cruiseRounds.size >= 2) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = SurfaceDark)
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                "Cruise Decay",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Spacer(Modifier.height(8.dp))
                            CruiseDecayChart(points = cruiseRounds)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Easy-phase duration per round — the downward slope shows CO₂ accumulating across the table.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                    }
                }

                // ── Round-by-round ─────────────────────────────────────────────
                if (state.roundResults.isNotEmpty()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = SurfaceDark)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                "Rounds",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            state.roundResults.sortedByDescending { it.roundNumber }.forEach { r ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = buildString {
                                            append("R${r.roundNumber}")
                                            append(" · rest ${r.restBeforeSec}s")
                                            if (r.endedEarly) append(" · ended early")
                                            else if (r.completed) append(" ✓")
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (r.completed) TextPrimary else TextSecondary
                                    )
                                    Text(
                                        text = buildString {
                                            r.cruiseSec?.let {
                                                append("cruise ${formatSec(it)}")
                                                r.cruiseRatio?.let { ratio ->
                                                    append(" (${(ratio * 100).toInt()}%)")
                                                }
                                            } ?: append("no contraction")
                                            if (state.mode == ContractionTableMode.CONTRACTION_COUNT) {
                                                append(" · ${r.contractions}c")
                                            }
                                            append(" · ${formatSec(r.totalHoldSec)}")
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = TextSecondary,
                                        textAlign = TextAlign.End
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Chart
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Simple line chart of cruise duration (sec) per round. Rounds with no logged
 * contraction are skipped (they break the cruise series).
 */
@Composable
private fun CruiseDecayChart(points: List<Pair<Int, Int>>) {
    val chartColor = ButtonPrimary
    val gridColor = SurfaceVariant

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(140.dp)
    ) {
        if (points.size < 2) return@Canvas

        val maxVal = points.maxOf { it.second }.toFloat().coerceAtLeast(1f)
        val minVal = 0f
        val range = (maxVal - minVal).coerceAtLeast(1f)

        val stepX = size.width / (points.size - 1)
        val chartHeight = size.height * 0.9f

        // Horizontal grid lines
        for (i in 0..2) {
            val y = chartHeight * i / 2f
            drawLine(
                color = gridColor,
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 1f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
            )
        }

        // Cruise line
        val path = Path()
        points.forEachIndexed { i, (_, value) ->
            val x = i * stepX
            val y = chartHeight - (value - minVal) / range * chartHeight
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path = path,
            color = chartColor,
            style = Stroke(width = 4f)
        )

        // Points
        points.forEachIndexed { i, (_, value) ->
            val x = i * stepX
            val y = chartHeight - (value - minVal) / range * chartHeight
            drawCircle(
                color = chartColor,
                radius = 6f,
                center = Offset(x, y)
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = TextPrimary)
    }
}

private fun formatSec(totalSec: Int): String {
    val m = totalSec / 60
    val s = totalSec % 60
    return "$m:${s.toString().padStart(2, '0')}"
}
