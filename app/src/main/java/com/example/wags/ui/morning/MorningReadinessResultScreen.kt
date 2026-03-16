package com.example.wags.ui.morning

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.wags.domain.model.HrvMetrics
import com.example.wags.domain.model.MorningReadinessResult
import com.example.wags.domain.model.ReadinessColor
import com.example.wags.ui.theme.*
import kotlin.math.ln

// ── Help text constants ───────────────────────────────────────────────────────

private const val HELP_READINESS_TITLE = "Readiness Score"
private const val HELP_READINESS_TEXT =
    "A comprehensive analysis of your Autonomic Nervous System's ability to handle stress today. " +
    "It combines your resting parasympathetic tone (HRV), your nervous system's elasticity " +
    "(how well you handle standing up), and your subjective wellness to guide your training."

private const val HELP_RMSSD_TITLE = "HRV (RMSSD)"
private const val HELP_RMSSD_TEXT =
    "Root Mean Square of Successive Differences. This is the gold standard for tracking daily " +
    "recovery. It measures the precise millisecond differences between your heartbeats, acting " +
    "as a direct window into your parasympathetic 'rest and digest' nervous system."

private const val HELP_HRV_SCORE_TITLE = "HRV Score (ln(RMSSD) × 20)"
private const val HELP_HRV_SCORE_TEXT =
    "Raw HRV numbers vary wildly between individuals. We apply a mathematical transformation " +
    "(natural log multiplied by 20) to plot your HRV on a smooth, easy-to-understand 0-100 scale."

private const val HELP_SUPINE_VS_STANDING_TITLE = "Supine vs Standing HRV"
private const val HELP_SUPINE_VS_STANDING_TEXT =
    "Laying down shows your absolute resting state, but very fit people can 'max out' this reading. " +
    "Standing up applies a gravitational stress to your heart. Measuring HRV while standing reveals " +
    "hidden fatigue and helps detect overtraining."

private const val HELP_3015_TITLE = "30:15 Ratio"
private const val HELP_3015_TEXT =
    "When you stand up, your heart rate spikes to pump blood to your brain, then your vagus nerve " +
    "kicks in to slow it back down. This ratio compares your fastest heartbeat (around beat 15) to " +
    "your slowest recovery heartbeat (around beat 30). A higher ratio means a highly elastic, " +
    "resilient nervous system."

private const val HELP_OHRR_TITLE = "Orthostatic HR Recovery (OHRR)"
private const val HELP_OHRR_TEXT =
    "Measures how fast your heart rate drops 20 and 60 seconds after the initial spike of standing " +
    "up. A fast drop means your body clears stress efficiently. A sluggish drop can indicate " +
    "dehydration, fatigue, or illness."

private const val HELP_SWC_TITLE = "Smallest Worthwhile Change (SWC)"
private const val HELP_SWC_TEXT =
    "Your body naturally fluctuates day to day. The SWC is a calculated 'buffer zone' based on " +
    "your personal baseline. If your score stays inside this zone, your body is stable and " +
    "absorbing stress well."

private const val HELP_CV_TITLE = "Coefficient of Variation (CV)"
private const val HELP_CV_TEXT =
    "The 'Variation of your Variability.' This tracks how wildly your HRV swings over a 7-day " +
    "period. A low CV (2-6%) means your body is steadily maintaining balance. A soaring CV means " +
    "your system is struggling to adapt and you may need a rest day."

private const val HELP_SLOW_BREATH_TITLE = "Slow Breathing Detected"
private const val HELP_SLOW_BREATH_TEXT =
    "We noticed you took slow, deep breaths during your test. Because deep breathing artificially " +
    "spikes HRV (a process called Respiratory Sinus Arrhythmia), we adjusted our algorithms today " +
    "to ensure your score remains accurate. Next time, try to breathe normally and unconsciously!"

// ── Main result composable ────────────────────────────────────────────────────

@Composable
fun MorningReadinessResultScreen(
    result: MorningReadinessResult,
    onReset: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        ReadinessScoreCard(result)

        if (result.slowBreathingFlagged) {
            SlowBreathingBanner()
        }

        HrvCard(
            title = "Supine HRV",
            metrics = result.supineHrvMetrics,
            showSupineVsStandingHelp = false
        )
        HrvCard(
            title = "Standing HRV",
            metrics = result.standingHrvMetrics,
            showSupineVsStandingHelp = true
        )

        OrthostaticCard(result)

        result.hooperIndex?.let { total ->
            HooperSummaryCard(result, total)
        }

        AlgorithmDetailsCard(result)

        Button(
            onClick = onReset,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = ButtonPrimary)
        ) {
            Text("New Session")
        }
    }
}

// ── Score card ────────────────────────────────────────────────────────────────

@Composable
private fun ReadinessScoreCard(result: MorningReadinessResult) {
    val scoreColor = when (result.readinessColor) {
        ReadinessColor.GREEN -> ReadinessGreen
        ReadinessColor.YELLOW -> ReadinessOrange
        ReadinessColor.RED -> ReadinessRed
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SurfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(20.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Readiness Score",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary
                )
                HelpBubble(title = HELP_READINESS_TITLE, text = HELP_READINESS_TEXT)
            }
            Text(
                text = result.readinessScore.toString(),
                style = MaterialTheme.typography.displayLarge,
                fontWeight = FontWeight.ExtraBold,
                color = scoreColor
            )
            Text(
                text = result.readinessColor.name,
                style = MaterialTheme.typography.labelLarge,
                color = scoreColor
            )
            Text(
                "Supine RHR: ${result.supineRhr} bpm",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
        }
    }
}

// ── Slow breathing banner ─────────────────────────────────────────────────────

@Composable
private fun SlowBreathingBanner() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = ReadinessOrange.copy(alpha = 0.15f))
    ) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "⚠ Slow Breathing Detected — score adjusted",
                style = MaterialTheme.typography.bodyMedium,
                color = ReadinessOrange,
                modifier = Modifier.weight(1f)
            )
            HelpBubble(title = HELP_SLOW_BREATH_TITLE, text = HELP_SLOW_BREATH_TEXT)
        }
    }
}

// ── HRV card ──────────────────────────────────────────────────────────────────

@Composable
private fun HrvCard(
    title: String,
    metrics: HrvMetrics,
    showSupineVsStandingHelp: Boolean
) {
    val lnRmssdScore = (metrics.lnRmssd * 20).toInt()
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = EcgCyan)
                if (showSupineVsStandingHelp) {
                    HelpBubble(
                        title = HELP_SUPINE_VS_STANDING_TITLE,
                        text = HELP_SUPINE_VS_STANDING_TEXT
                    )
                }
            }
            MetricRowWithHelp(
                label = "RMSSD",
                value = "${String.format("%.1f", metrics.rmssdMs)} ms",
                helpTitle = HELP_RMSSD_TITLE,
                helpText = HELP_RMSSD_TEXT
            )
            MetricRowWithHelp(
                label = "HRV Score (ln×20)",
                value = lnRmssdScore.toString(),
                helpTitle = HELP_HRV_SCORE_TITLE,
                helpText = HELP_HRV_SCORE_TEXT
            )
            SimpleMetricRow(
                label = "SDNN",
                value = "${String.format("%.1f", metrics.sdnnMs)} ms"
            )
            SimpleMetricRow(
                label = "Samples",
                value = metrics.sampleCount.toString()
            )
        }
    }
}

// ── Orthostatic card ──────────────────────────────────────────────────────────

@Composable
private fun OrthostaticCard(result: MorningReadinessResult) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Orthostatic Response", style = MaterialTheme.typography.titleMedium, color = EcgCyan)
            SimpleMetricRow("Peak Stand HR", "${result.peakStandHr} bpm")

            result.thirtyFifteenRatio?.let { ratio ->
                MetricRowWithHelp(
                    label = "30:15 Ratio",
                    value = String.format("%.3f", ratio),
                    helpTitle = HELP_3015_TITLE,
                    helpText = HELP_3015_TEXT
                )
            }
            result.ohrrAt20s?.let { ohrr20 ->
                MetricRowWithHelp(
                    label = "OHRR at 20s",
                    value = "${String.format("%.1f", ohrr20)} %",
                    helpTitle = HELP_OHRR_TITLE,
                    helpText = HELP_OHRR_TEXT
                )
            }
            result.ohrrAt60s?.let { ohrr60 ->
                SimpleMetricRow(
                    label = "OHRR at 60s",
                    value = "${String.format("%.1f", ohrr60)} %"
                )
            }
        }
    }
}

// ── Hooper summary card ───────────────────────────────────────────────────────

@Composable
private fun HooperSummaryCard(result: MorningReadinessResult, total: Float) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SurfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Hooper Wellness Index", style = MaterialTheme.typography.titleMedium, color = EcgCyan)
            Text(
                "Total: ${String.format("%.0f", total)} / 20",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            result.hooperSleep?.let    { SimpleMetricRow("Sleep Quality",   "${String.format("%.1f", it)} / 5") }
            result.hooperFatigue?.let  { SimpleMetricRow("Fatigue",         "${String.format("%.1f", it)} / 5") }
            result.hooperSoreness?.let { SimpleMetricRow("Muscle Soreness", "${String.format("%.1f", it)} / 5") }
            result.hooperStress?.let   { SimpleMetricRow("Stress",          "${String.format("%.1f", it)} / 5") }
        }
    }
}

// ── Algorithm details card ────────────────────────────────────────────────────

@Composable
private fun AlgorithmDetailsCard(result: MorningReadinessResult) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Algorithm Details", style = MaterialTheme.typography.titleMedium, color = TextSecondary)

            SimpleMetricRow("HRV Base Score", result.hvBaseScore.toString())
            SimpleMetricRow("Ortho Multiplier", String.format("%.2f", result.orthoMultiplier))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("CV Penalty", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                    HelpBubble(title = HELP_CV_TITLE, text = HELP_CV_TEXT)
                }
                FlagChip(applied = result.cvPenaltyApplied)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("RHR Limiter", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                    HelpBubble(title = HELP_SWC_TITLE, text = HELP_SWC_TEXT)
                }
                FlagChip(applied = result.rhrLimiterApplied)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Artifact % (Supine)", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                Text(
                    "${String.format("%.1f", result.artifactPercentSupine)}%",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Artifact % (Standing)", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                Text(
                    "${String.format("%.1f", result.artifactPercentStanding)}%",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary
                )
            }
        }
    }
}

// ── Shared helpers ────────────────────────────────────────────────────────────

@Composable
private fun SimpleMetricRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        Text(value, style = MaterialTheme.typography.bodyLarge, color = TextPrimary, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun FlagChip(applied: Boolean) {
    val (label, color) = if (applied) "Applied" to ReadinessOrange else "None" to ReadinessGreen
    Surface(
        shape = MaterialTheme.shapes.small,
        color = color.copy(alpha = 0.15f)
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelMedium,
            color = color
        )
    }
}
