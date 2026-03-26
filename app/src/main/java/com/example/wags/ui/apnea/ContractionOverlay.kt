package com.example.wags.ui.apnea

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.wags.MainActivity
import com.example.wags.domain.usecase.apnea.ApneaState
import com.example.wags.ui.common.InfoHelpBubble
import com.example.wags.ui.theme.SurfaceVariant
import com.example.wags.ui.theme.TextSecondary

private const val CONTRACTION_HELP_TITLE = "Diaphragmatic Contractions"
private const val CONTRACTION_HELP_CONTENT = """
Purpose: Involuntary diaphragm contractions signal rising CO₂ levels.

Phases:
• Cruising Phase: Time from hold start to first contraction (aerobic zone)
• Struggle Phase: Time from first contraction to hold end (anaerobic zone)

Training insight: A longer Cruising Phase indicates better CO₂ tolerance.
The ratio Cruising/Total Hold is your "efficiency score".

Formula: Efficiency = T_cruise / T_total × 100%
• T_cruise = Time to first contraction
• T_total = Total hold duration
"""

/**
 * Full-screen transparent overlay active during APNEA phase.
 * - Double-tap anywhere → logs a contraction
 * - Volume buttons (via MainActivity) → logs a contraction
 * - Shows live contraction counter badge (top-right)
 * Must be placed BEFORE the main content in the Z-order so existing buttons remain tappable.
 */
@Composable
fun ContractionOverlay(
    uiState: ApneaUiState,
    onLogContraction: () -> Unit
) {
    val activity = LocalContext.current as? MainActivity

    // Keep MainActivity flags in sync with the current phase
    val isApneaActive = uiState.apneaState == ApneaState.APNEA
    LaunchedEffect(isApneaActive) {
        activity?.isApneaHoldActive = isApneaActive
    }
    DisposableEffect(Unit) {
        activity?.onContractionLogged = { onLogContraction() }
        onDispose {
            activity?.isApneaHoldActive = false
            activity?.onContractionLogged = null
        }
    }

    if (isApneaActive) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = { onLogContraction() })
                }
        ) {
            if (uiState.contractionCount > 0) {
                Text(
                    text = "Contractions: ${uiState.contractionCount}",
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp),
                    color = TextSecondary,
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}

/**
 * Summary card shown during RECOVERY phase with cruising time, struggle time, and contraction count.
 */
@Composable
fun ContractionSummaryCard(uiState: ApneaUiState) {
    if (uiState.apneaState != ApneaState.RECOVERY) return

    val firstMs = uiState.firstContractionElapsedMs
    val holdMs = uiState.lastHoldDurationMs

    val cruising = if (firstMs != null) formatMmSs(firstMs) else "—"
    val struggle = if (firstMs != null && holdMs > 0L) formatMmSs(holdMs - firstMs) else "—"
    val count = uiState.contractionCount

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SurfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SummaryItem(label = "Cruising", value = cruising)
            SummaryItem(label = "Struggle", value = struggle)
            SummaryItem(label = "Contractions", value = count.toString(), showHelp = true)
        }
    }
}

@Composable
private fun SummaryItem(label: String, value: String, showHelp: Boolean = false) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = Color(0xFFAAAAAA))
            if (showHelp) {
                InfoHelpBubble(
                    title = CONTRACTION_HELP_TITLE,
                    content = CONTRACTION_HELP_CONTENT
                )
            }
        }
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun formatMmSs(ms: Long): String {
    val totalSecs = (ms / 1000L).coerceAtLeast(0L)
    val mins = totalSecs / 60
    val secs = totalSecs % 60
    return if (mins > 0) "${mins}m ${secs}s" else "${secs}s"
}
