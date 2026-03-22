package com.example.wags.ui.breathing

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.wags.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

// ---------------------------------------------------------------------------
// Colors
// ---------------------------------------------------------------------------

private val GoldAccent = Color(0xFFFFD700)
private val ChartCyan = Color(0xFF00E5FF)
private val ChartMagenta = Color(0xFFE040FB)
private val ChartGreen = Color(0xFF66BB6A)
private val ChartOrange = Color(0xFFFF9800)
private val Graphite = Color(0xFF383838)
private val Charcoal = Color(0xFF1C1C1C)
private val Ink = Color(0xFF0A0A0A)
private val Bone = Color(0xFFE8E8E8)
private val Silver = Color(0xFFB0B0B0)
private val Ash = Color(0xFF707070)

// ---------------------------------------------------------------------------
// Main screen
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssessmentResultScreen(
    sessionTimestamp: Long,
    onNavigateBack: () -> Unit,
    onRunAgain: () -> Unit,
    viewModel: AssessmentResultViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showDeleteDialog by remember { mutableStateOf(false) }

    // Pop back when deletion completes
    LaunchedEffect(Unit) {
        viewModel.deleted.collect {
            onNavigateBack()
        }
    }

    // Delete confirmation dialog
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            containerColor = SurfaceDark,
            title = { Text("Delete Assessment?", color = Bone) },
            text = {
                Text(
                    "This will permanently remove this assessment record. This cannot be undone.",
                    color = Silver
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    viewModel.deleteSession()
                }) {
                    Text("Delete", color = ButtonDanger)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel", color = EcgCyan)
                }
            }
        )
    }

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = { Text("Assessment Results") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Text("←", style = MaterialTheme.typography.headlineMedium, color = EcgCyan)
                    }
                },
                actions = {
                    if (!state.isLoading) {
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(
                                imageVector = Icons.Outlined.Delete,
                                contentDescription = "Delete assessment",
                                tint = ButtonDanger
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark)
            )
        },
        bottomBar = {
            BottomButtons(onRunAgain = onRunAgain, onDone = onNavigateBack)
        }
    ) { padding ->
        if (state.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = EcgCyan)
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            val session = state.currentSession

            // ═══════════════════════════════════════════════════════════════
            // 1. RECOMMENDED BREATHING RATE — The hero section
            // ═══════════════════════════════════════════════════════════════
            if (session != null) {
                RecommendedRateCard(
                    optimalBpm = session.optimalBpm,
                    isValid = session.isValid,
                    protocolType = session.protocolType
                )
            }

            // ═══════════════════════════════════════════════════════════════
            // 2. RESONANCE CURVE GRAPH
            // ═══════════════════════════════════════════════════════════════
            if (session != null && session.resonanceCurveJson.isNotBlank() && session.resonanceCurveJson != "[]") {
                val curvePoints = remember(session.resonanceCurveJson) {
                    parseResonanceCurve(session.resonanceCurveJson)
                }
                if (curvePoints.isNotEmpty()) {
                    SectionHeader("Resonance Curve")
                    ResonanceCurveChart(
                        points = curvePoints,
                        optimalBpm = session.optimalBpm,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                    )
                }
            }

            // ═══════════════════════════════════════════════════════════════
            // 3. HR WAVEFORM SNAPSHOT
            // ═══════════════════════════════════════════════════════════════
            if (session != null && session.hrWaveformJson.isNotBlank() && session.hrWaveformJson != "[]") {
                val waveformPoints = remember(session.hrWaveformJson) {
                    parseHrWaveform(session.hrWaveformJson)
                }
                if (waveformPoints.isNotEmpty()) {
                    SectionHeader("Heart Rate Waveform at Resonance")
                    HrWaveformChart(
                        points = waveformPoints,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                    )
                }
            }

            // ═══════════════════════════════════════════════════════════════
            // 4. POWER SPECTRUM
            // ═══════════════════════════════════════════════════════════════
            if (session != null && session.powerSpectrumJson.isNotBlank() && session.powerSpectrumJson != "[]") {
                val spectrumPoints = remember(session.powerSpectrumJson) {
                    parsePowerSpectrum(session.powerSpectrumJson)
                }
                if (spectrumPoints.isNotEmpty()) {
                    SectionHeader("Power Spectrum")
                    PowerSpectrumChart(
                        points = spectrumPoints,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                    )
                }
            }

            // ═══════════════════════════════════════════════════════════════
            // 5. ASSESSMENT METRICS TABLE
            // ═══════════════════════════════════════════════════════════════
            if (session != null) {
                SectionHeader("Assessment Metrics")
                MetricsTable(session = session)
            }

            // ═══════════════════════════════════════════════════════════════
            // 6. LEADERBOARD (for stepped protocols)
            // ═══════════════════════════════════════════════════════════════
            if (session != null && session.leaderboardJson.startsWith("[")) {
                val entries = remember(session.leaderboardJson) {
                    parseLeaderboard(session.leaderboardJson)
                }
                if (entries.isNotEmpty()) {
                    SectionHeader("Rate Comparison")
                    entries.sortedByDescending { it.score }.forEachIndexed { index, entry ->
                        LeaderboardRow(rank = index + 1, entry = entry, isOptimal = entry.bpm == session.optimalBpm)
                    }
                }
            }

            // ═══════════════════════════════════════════════════════════════
            // 7. HISTORY
            // ═══════════════════════════════════════════════════════════════
            if (state.allSessions.size > 1) {
                SectionHeader("Assessment History")
                val dateFormat = remember { SimpleDateFormat("MMM d, yyyy  HH:mm", Locale.getDefault()) }
                state.allSessions.take(10).forEach { s ->
                    HistoryRow(session = s, dateFormat = dateFormat, isCurrent = s.timestamp == session?.timestamp)
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// RECOMMENDED RATE CARD — The most important element
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun RecommendedRateCard(
    optimalBpm: Float,
    isValid: Boolean,
    protocolType: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isValid) Color(0xFF0D2818) else SurfaceVariant
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(24.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = if (isValid) "YOUR RESONANCE FREQUENCY" else "ASSESSMENT INCOMPLETE",
                style = MaterialTheme.typography.labelMedium,
                color = if (isValid) GoldAccent else ReadinessOrange,
                letterSpacing = 3.sp,
                fontWeight = FontWeight.Bold
            )

            if (isValid && optimalBpm > 0f) {
                Text(
                    text = "%.1f".format(optimalBpm),
                    style = MaterialTheme.typography.displayLarge.copy(
                        fontSize = 64.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    color = Color.White
                )
                Text(
                    text = "breaths per minute",
                    style = MaterialTheme.typography.titleMedium,
                    color = Silver
                )

                Spacer(Modifier.height(8.dp))

                // Explanation text
                Text(
                    text = "This is your ideal breathing rate for resonance breathing sessions. " +
                            "Breathing at this rate maximizes your heart rate variability and " +
                            "strengthens your autonomic nervous system.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Ash,
                    textAlign = TextAlign.Center
                )
            } else {
                Text(
                    text = "Unable to determine resonance frequency. " +
                            "Try again with a chest strap sensor and follow the pacer closely.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(Modifier.height(4.dp))
            Text(
                text = "Protocol: $protocolType",
                style = MaterialTheme.typography.labelSmall,
                color = Ash
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// RESONANCE CURVE CHART
// ═══════════════════════════════════════════════════════════════════════════

private data class ResonanceCurvePoint(
    val bpm: Float,
    val score: Float,
    val ptAmp: Float = 0f,
    val valid: Boolean = true
)

private fun parseResonanceCurve(json: String): List<ResonanceCurvePoint> {
    if (json.isBlank() || json == "[]") return emptyList()
    return try {
        val arr = org.json.JSONArray(json)
        (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            ResonanceCurvePoint(
                bpm = obj.optDouble("bpm", 0.0).toFloat(),
                score = obj.optDouble("score", obj.optDouble("lfPower", 0.0)).toFloat(),
                ptAmp = obj.optDouble("ptAmp", 0.0).toFloat(),
                valid = obj.optBoolean("valid", true)
            )
        }
    } catch (_: Exception) { emptyList() }
}

@Composable
private fun ResonanceCurveChart(
    points: List<ResonanceCurvePoint>,
    optimalBpm: Float,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                Brush.verticalGradient(listOf(Ink, Charcoal.copy(alpha = 0.3f), Ink))
            )
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(start = 36.dp, end = 12.dp, top = 12.dp, bottom = 24.dp)) {
            if (points.size < 2) return@Canvas
            val w = size.width
            val h = size.height

            val minBpm = points.minOf { it.bpm }
            val maxBpm = points.maxOf { it.bpm }
            val bpmRange = (maxBpm - minBpm).coerceAtLeast(0.5f)
            val maxScore = points.maxOf { it.score }.coerceAtLeast(1f)

            fun xAt(bpm: Float) = ((bpm - minBpm) / bpmRange * w)
            fun yAt(score: Float) = h - (score / maxScore * h)

            val labelPaint = android.graphics.Paint().apply {
                color = 0xFF707070.toInt()
                textSize = 9.sp.toPx()
                isAntiAlias = true
            }

            // Draw grid lines + Y-axis labels
            val yTicks = 4
            for (i in 0..yTicks) {
                val y = h * i / yTicks.toFloat()
                drawLine(Graphite.copy(alpha = 0.3f), Offset(0f, y), Offset(w, y), strokeWidth = 1f)
                val value = maxScore * (yTicks - i) / yTicks
                val label = if (maxScore > 100) "%.0f".format(value) else "%.1f".format(value)
                drawContext.canvas.nativeCanvas.drawText(
                    label, -34.dp.toPx(), y + 4.dp.toPx(), labelPaint
                )
            }

            // X-axis labels
            val sorted = points.sortedBy { it.bpm }
            val xLabelPaint = android.graphics.Paint().apply {
                color = 0xFF707070.toInt()
                textSize = 9.sp.toPx()
                isAntiAlias = true
                textAlign = android.graphics.Paint.Align.CENTER
            }
            sorted.forEach { pt ->
                val x = xAt(pt.bpm)
                drawContext.canvas.nativeCanvas.drawText(
                    "%.1f".format(pt.bpm), x, h + 14.dp.toPx(), xLabelPaint
                )
            }

            // Draw curve
            val path = Path()
            sorted.forEachIndexed { idx, pt ->
                val x = xAt(pt.bpm)
                val y = yAt(pt.score)
                if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, ChartCyan, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))

            // Draw dots
            sorted.forEach { pt ->
                val x = xAt(pt.bpm)
                val y = yAt(pt.score)
                val isOptimal = kotlin.math.abs(pt.bpm - optimalBpm) < 0.05f
                val dotColor = if (isOptimal) GoldAccent else if (pt.valid) ChartCyan else ReadinessRed
                val dotRadius = if (isOptimal) 8.dp.toPx() else 5.dp.toPx()
                drawCircle(dotColor, radius = dotRadius, center = Offset(x, y))
                if (isOptimal) {
                    drawCircle(GoldAccent.copy(alpha = 0.3f), radius = 14.dp.toPx(), center = Offset(x, y))
                }
            }
        }

        // X-axis title
        Text(
            text = "Breathing Rate (BPM)",
            style = MaterialTheme.typography.labelSmall,
            color = Ash,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 1.dp)
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// HR WAVEFORM CHART
// ═══════════════════════════════════════════════════════════════════════════

private data class HrWaveformPoint(val timeMs: Float, val hrBpm: Float)

private fun parseHrWaveform(json: String): List<HrWaveformPoint> {
    if (json.isBlank() || json == "[]") return emptyList()
    return try {
        val arr = org.json.JSONArray(json)
        (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            HrWaveformPoint(
                timeMs = obj.optDouble("t", 0.0).toFloat(),
                hrBpm = obj.optDouble("hr", 0.0).toFloat()
            )
        }
    } catch (_: Exception) { emptyList() }
}

@Composable
private fun HrWaveformChart(
    points: List<HrWaveformPoint>,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Brush.verticalGradient(listOf(Ink, Charcoal.copy(alpha = 0.3f), Ink)))
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(start = 36.dp, end = 12.dp, top = 12.dp, bottom = 24.dp)) {
            if (points.size < 2) return@Canvas
            val w = size.width
            val h = size.height

            val minT = points.minOf { it.timeMs }
            val maxT = points.maxOf { it.timeMs }
            val tRange = (maxT - minT).coerceAtLeast(1f)
            val minHr = points.minOf { it.hrBpm }
            val maxHr = points.maxOf { it.hrBpm }
            val hrRange = (maxHr - minHr).coerceAtLeast(1f)
            val paddedMin = minHr - hrRange * 0.1f
            val paddedMax = maxHr + hrRange * 0.1f
            val paddedRange = paddedMax - paddedMin

            val labelPaint = android.graphics.Paint().apply {
                color = 0xFF707070.toInt()
                textSize = 9.sp.toPx()
                isAntiAlias = true
            }
            val xLabelPaint = android.graphics.Paint().apply {
                color = 0xFF707070.toInt()
                textSize = 9.sp.toPx()
                isAntiAlias = true
                textAlign = android.graphics.Paint.Align.CENTER
            }

            // Grid + Y-axis labels
            val yTicks = 4
            for (i in 0..yTicks) {
                val y = h * i / yTicks.toFloat()
                drawLine(Graphite.copy(alpha = 0.3f), Offset(0f, y), Offset(w, y), strokeWidth = 1f)
                val value = paddedMax - (paddedMax - paddedMin) * i / yTicks
                drawContext.canvas.nativeCanvas.drawText(
                    "%.0f".format(value), -34.dp.toPx(), y + 4.dp.toPx(), labelPaint
                )
            }

            // X-axis labels (time in seconds, 3-4 ticks)
            val totalSec = tRange / 1000f
            val xTicks = if (totalSec > 30) 4 else 3
            for (i in 0..xTicks) {
                val timeSec = totalSec * i / xTicks
                val x = w * i / xTicks.toFloat()
                drawContext.canvas.nativeCanvas.drawText(
                    "%.0fs".format(timeSec), x, h + 14.dp.toPx(), xLabelPaint
                )
            }

            // Waveform
            val path = Path()
            points.forEachIndexed { idx, pt ->
                val x = (pt.timeMs - minT) / tRange * w
                val y = h - ((pt.hrBpm - paddedMin) / paddedRange * h)
                if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, ChartGreen, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }

        // Axis title
        Text(
            text = "HR (BPM) — sine-wave = resonance",
            style = MaterialTheme.typography.labelSmall,
            color = Ash,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 1.dp)
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// POWER SPECTRUM CHART
// ═══════════════════════════════════════════════════════════════════════════

private data class SpectrumPoint(val freqHz: Float, val powerMs2: Float)

private fun parsePowerSpectrum(json: String): List<SpectrumPoint> {
    if (json.isBlank() || json == "[]") return emptyList()
    return try {
        val arr = org.json.JSONArray(json)
        (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            SpectrumPoint(
                freqHz = obj.optDouble("f", 0.0).toFloat(),
                powerMs2 = obj.optDouble("p", 0.0).toFloat()
            )
        }
    } catch (_: Exception) { emptyList() }
}

@Composable
private fun PowerSpectrumChart(
    points: List<SpectrumPoint>,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Brush.verticalGradient(listOf(Ink, Charcoal.copy(alpha = 0.3f), Ink)))
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(start = 40.dp, end = 12.dp, top = 12.dp, bottom = 24.dp)) {
            if (points.size < 2) return@Canvas
            val w = size.width
            val h = size.height

            // Only show 0–0.5 Hz
            val filtered = points.filter { it.freqHz in 0f..0.5f }
            if (filtered.size < 2) return@Canvas

            val maxFreq = 0.5f
            val maxPower = filtered.maxOf { it.powerMs2 }.coerceAtLeast(1f)

            val labelPaint = android.graphics.Paint().apply {
                color = 0xFF707070.toInt()
                textSize = 9.sp.toPx()
                isAntiAlias = true
            }
            val xLabelPaint = android.graphics.Paint().apply {
                color = 0xFF707070.toInt()
                textSize = 9.sp.toPx()
                isAntiAlias = true
                textAlign = android.graphics.Paint.Align.CENTER
            }

            // LF band highlight (0.04–0.15 Hz)
            val lfStartX = 0.04f / maxFreq * w
            val lfEndX = 0.15f / maxFreq * w
            drawRect(
                color = ChartCyan.copy(alpha = 0.08f),
                topLeft = Offset(lfStartX, 0f),
                size = androidx.compose.ui.geometry.Size(lfEndX - lfStartX, h)
            )

            // Grid + Y-axis labels
            val yTicks = 4
            for (i in 0..yTicks) {
                val y = h * i / yTicks.toFloat()
                drawLine(Graphite.copy(alpha = 0.3f), Offset(0f, y), Offset(w, y), strokeWidth = 1f)
                val value = maxPower * (yTicks - i) / yTicks
                val label = if (maxPower > 1000) "%.0f".format(value) else "%.1f".format(value)
                drawContext.canvas.nativeCanvas.drawText(
                    label, -38.dp.toPx(), y + 4.dp.toPx(), labelPaint
                )
            }

            // X-axis labels (frequency in Hz, 3 ticks)
            val freqTicks = listOf(0f, 0.1f, 0.2f, 0.3f, 0.4f, 0.5f)
            freqTicks.forEach { freq ->
                val x = freq / maxFreq * w
                drawContext.canvas.nativeCanvas.drawText(
                    "%.1f".format(freq), x, h + 14.dp.toPx(), xLabelPaint
                )
            }

            // Spectrum fill
            val fillPath = Path()
            fillPath.moveTo(0f, h)
            filtered.forEach { pt ->
                val x = pt.freqHz / maxFreq * w
                val y = h - (pt.powerMs2 / maxPower * h)
                fillPath.lineTo(x, y)
            }
            fillPath.lineTo(filtered.last().freqHz / maxFreq * w, h)
            fillPath.close()
            drawPath(fillPath, ChartMagenta.copy(alpha = 0.2f))

            // Spectrum line
            val linePath = Path()
            filtered.forEachIndexed { idx, pt ->
                val x = pt.freqHz / maxFreq * w
                val y = h - (pt.powerMs2 / maxPower * h)
                if (idx == 0) linePath.moveTo(x, y) else linePath.lineTo(x, y)
            }
            drawPath(linePath, ChartMagenta, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
        }

        // Axis titles
        Row(
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 1.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("LF Band", style = MaterialTheme.typography.labelSmall, color = ChartCyan.copy(alpha = 0.7f))
            Text("Frequency (Hz)", style = MaterialTheme.typography.labelSmall, color = Ash)
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// METRICS TABLE
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun MetricsTable(session: com.example.wags.data.db.entity.RfAssessmentEntity) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            MetricRow("Optimal Breathing Rate", "%.1f BPM".format(session.optimalBpm), "Target pace for future sessions")
            HorizontalDivider(color = SurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))
            MetricRow("Peak-to-Trough Amplitude", "%.1f BPM".format(session.peakToTroughBpm), "Vagal response strength")
            HorizontalDivider(color = SurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))
            MetricRow("Max LF Power", "%.0f ms²/Hz".format(session.maxLfPowerMs2), "Baroreflex stimulation energy")
            HorizontalDivider(color = SurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))
            MetricRow("Max Coherence Ratio", "%.1f".format(session.maxCoherenceRatio), "Autonomic synchronization purity")
            HorizontalDivider(color = SurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))
            MetricRow("Mean RMSSD", "%.1f ms".format(session.meanRmssdMs), "Parasympathetic tone")
            HorizontalDivider(color = SurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))
            MetricRow("Mean SDNN", "%.1f ms".format(session.meanSdnnMs), "Overall HRV")
            HorizontalDivider(color = SurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))
            MetricRow("Duration", formatDuration(session.durationSeconds), "Assessment length")
            HorizontalDivider(color = SurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))
            MetricRow("Total Beats", "${session.totalBeats}", "RR intervals collected")
            HorizontalDivider(color = SurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))
            MetricRow("Artifact %", "%.1f%%".format(session.artifactPercent), "Data quality")
            HorizontalDivider(color = SurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))
            MetricRow("Composite Score", "%.1f".format(session.compositeScore), "Overall assessment quality")
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String, description: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary
            )
            Text(
                text = description,
                style = MaterialTheme.typography.labelSmall,
                color = Ash
            )
        }
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = EcgCyan,
            fontWeight = FontWeight.Bold
        )
    }
}

private fun formatDuration(seconds: Int): String {
    val mins = seconds / 60
    val secs = seconds % 60
    return "%d:%02d".format(mins, secs)
}

// ═══════════════════════════════════════════════════════════════════════════
// LEADERBOARD ROW
// ═══════════════════════════════════════════════════════════════════════════

private data class LeaderboardEntry(
    val bpm: Float,
    val score: Float,
    val isValid: Boolean,
    val ieRatio: Float
)

private fun parseLeaderboard(json: String): List<LeaderboardEntry> {
    if (json.isBlank()) return emptyList()
    return try {
        val arr = org.json.JSONArray(json)
        (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            LeaderboardEntry(
                bpm     = obj.optDouble("bpm", 0.0).toFloat(),
                score   = obj.optDouble("score", 0.0).toFloat(),
                isValid = obj.optBoolean("valid", true),
                ieRatio = obj.optDouble("ie", 1.0).toFloat()
            )
        }
    } catch (_: org.json.JSONException) {
        emptyList()
    }
}

@Composable
private fun LeaderboardRow(rank: Int, entry: LeaderboardEntry, isOptimal: Boolean) {
    val bgColor = if (isOptimal) Color(0xFF0D2818) else SurfaceDark
    val accentColor = if (isOptimal) GoldAccent else if (entry.isValid) EcgCyan else ReadinessRed

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = bgColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "#$rank",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    modifier = Modifier.width(28.dp)
                )
                if (isOptimal) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(GoldAccent)
                    )
                }
                Text(
                    text = "%.1f BPM".format(entry.bpm),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary,
                    fontWeight = if (isOptimal) FontWeight.Bold else FontWeight.Normal
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "%.1f".format(entry.score),
                    style = MaterialTheme.typography.bodyMedium,
                    color = accentColor,
                    fontWeight = FontWeight.SemiBold
                )
                if (!entry.isValid) {
                    Text("⚠", color = ReadinessOrange, style = MaterialTheme.typography.bodyMedium)
                }
                if (isOptimal) {
                    Text("★", color = GoldAccent, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// HISTORY ROW
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun HistoryRow(
    session: com.example.wags.data.db.entity.RfAssessmentEntity,
    dateFormat: SimpleDateFormat,
    isCurrent: Boolean
) {
    val bgColor = if (isCurrent) SurfaceVariant else SurfaceDark
    Card(colors = CardDefaults.cardColors(containerColor = bgColor)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = dateFormat.format(Date(session.timestamp)),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                Text(
                    text = "${session.protocolType}  •  ${"%.1f".format(session.optimalBpm)} BPM",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "%.1f".format(session.compositeScore),
                    style = MaterialTheme.typography.bodyLarge,
                    color = EcgCyan,
                    fontWeight = FontWeight.Bold
                )
                if (isCurrent) {
                    Text("current", style = MaterialTheme.typography.labelSmall, color = GoldAccent)
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// SHARED HELPERS
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = Silver,
        letterSpacing = 2.sp,
        modifier = Modifier.padding(top = 4.dp)
    )
}

@Composable
private fun BottomButtons(onRunAgain: () -> Unit, onDone: () -> Unit) {
    Surface(color = SurfaceDark) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick  = onRunAgain,
                modifier = Modifier.weight(1f),
                colors   = ButtonDefaults.outlinedButtonColors(contentColor = EcgCyan)
            ) {
                Text("Run Again")
            }
            Button(
                onClick  = onDone,
                modifier = Modifier.weight(1f),
                colors   = ButtonDefaults.buttonColors(containerColor = ButtonPrimary)
            ) {
                Text("Done")
            }
        }
    }
}
