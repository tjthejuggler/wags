package com.example.wags.ui.breathing

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.wags.data.db.entity.ResonanceSessionEntity
import com.example.wags.ui.theme.*
import org.json.JSONArray
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ── Palette ────────────────────────────────────────────────────────────────────
private val DetailBone   = Color(0xFFE8E8E8)
private val DetailSilver = Color(0xFFB0B0B0)
private val DetailAsh    = Color(0xFF707070)
private val ZoneGreen    = Color(0xFFD0D0D0)
private val ZoneBlue     = Color(0xFF909090)
private val ZoneRed      = Color(0xFF606060)

// ── Screen ─────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ResonanceSessionDetailScreen(
    onNavigateBack: () -> Unit,
    viewModel: ResonanceSessionDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showDeleteDialog by remember { mutableStateOf(false) }

    // Pop back when deleted
    LaunchedEffect(Unit) {
        viewModel.deleted.collect { onNavigateBack() }
    }

    // Pager state
    val pageCount = uiState.allSessionIds.size.coerceAtLeast(1)
    val pagerState = androidx.compose.foundation.pager.rememberPagerState(
        initialPage = uiState.currentIndex,
        pageCount   = { pageCount }
    )

    // Sync pager → ViewModel when user swipes
    LaunchedEffect(pagerState.currentPage) {
        viewModel.navigateToIndex(pagerState.currentPage)
    }

    // Sync ViewModel → pager (e.g. after a delete moves to adjacent session)
    LaunchedEffect(uiState.currentIndex) {
        if (pagerState.currentPage != uiState.currentIndex) {
            pagerState.scrollToPage(uiState.currentIndex)
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            containerColor   = SurfaceVariant,
            title = {
                Text(
                    "Delete this session?",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary
                )
            },
            text = {
                Text(
                    "This will permanently remove the resonance session record. This cannot be undone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    viewModel.deleteSession()
                }) { Text("Delete", color = TextDisabled, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            }
        )
    }

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Session Details")
                        if (uiState.allSessionIds.size > 1) {
                            Text(
                                "${uiState.currentIndex + 1} / ${uiState.allSessionIds.size}",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextDisabled
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Text("←", style = MaterialTheme.typography.headlineMedium, color = TextSecondary)
                    }
                },
                actions = {
                    IconButton(onClick = { showDeleteDialog = true }) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "Delete session",
                            tint = TextSecondary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark)
            )
        }
    ) { padding ->
        androidx.compose.foundation.pager.HorizontalPager(
            state    = pagerState,
            modifier = Modifier.fillMaxSize().padding(padding)
        ) { _ ->
            when {
                uiState.isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = EcgCyan)
                    }
                }
                uiState.session == null -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Session not found.", color = TextSecondary)
                    }
                }
                else -> {
                    SessionDetailContent(
                        session = uiState.session!!,
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp)
                    )
                }
            }
        }
    }
}

// ── Content ────────────────────────────────────────────────────────────────────

@Composable
private fun SessionDetailContent(
    session: ResonanceSessionEntity,
    modifier: Modifier = Modifier
) {
    val zone = ZoneId.systemDefault()
    val dateTime = Instant.ofEpochMilli(session.timestamp).atZone(zone)
    val dateLabel = dateTime.format(DateTimeFormatter.ofPattern("EEEE, MMMM d yyyy"))
    val timeLabel = dateTime.format(DateTimeFormatter.ofPattern("h:mm a"))
    val durationMin = session.durationSeconds / 60
    val durationSec = session.durationSeconds % 60

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ── Hero card ──────────────────────────────────────────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "RESONANCE SESSION",
                    style = MaterialTheme.typography.labelMedium,
                    color = DetailSilver,
                    letterSpacing = 3.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "%.2f".format(session.meanCoherenceRatio),
                    style = MaterialTheme.typography.displayLarge.copy(
                        fontSize = 56.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    color = TextPrimary
                )
                Text(
                    text = "mean coherence",
                    style = MaterialTheme.typography.titleMedium,
                    color = DetailSilver
                )
                Text(
                    text = "$dateLabel  •  $timeLabel",
                    style = MaterialTheme.typography.bodySmall,
                    color = DetailAsh,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "%.1f BPM  •  %d:%02d".format(
                        session.breathingRateBpm, durationMin, durationSec
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = DetailAsh
                )
            }
        }

        // ── Coherence zone breakdown ───────────────────────────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Coherence Zone Breakdown",
                    style = MaterialTheme.typography.titleSmall,
                    color = DetailSilver,
                    letterSpacing = 2.sp
                )
                val totalSec = (session.timeInHighCoherence + session.timeInMediumCoherence + session.timeInLowCoherence)
                    .coerceAtLeast(1)
                ZoneBar(label = "HIGH",   seconds = session.timeInHighCoherence,   totalSeconds = totalSec, color = ZoneGreen)
                ZoneBar(label = "MEDIUM", seconds = session.timeInMediumCoherence, totalSeconds = totalSec, color = ZoneBlue)
                ZoneBar(label = "LOW",    seconds = session.timeInLowCoherence,    totalSeconds = totalSec, color = ZoneRed)
            }
        }

        // ── Metrics card ───────────────────────────────────────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Session Metrics",
                    style = MaterialTheme.typography.titleSmall,
                    color = DetailSilver,
                    letterSpacing = 2.sp
                )
                HorizontalDivider(color = SurfaceVariant)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    MetricChip("Mean Coherence", "%.2f".format(session.meanCoherenceRatio))
                    MetricChip("Max Coherence", "%.2f".format(session.maxCoherenceRatio))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    MetricChip("RMSSD", "%.1f ms".format(session.meanRmssdMs))
                    MetricChip("SDNN", "%.1f ms".format(session.meanSdnnMs))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    MetricChip("Total Beats", session.totalBeats.toString())
                    MetricChip("Artifact", "%.1f%%".format(session.artifactPercent))
                }
                if (session.hrDeviceId != null) {
                    Text(
                        "Device: ${session.hrDeviceId}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextDisabled,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        // ── Coherence over time chart ──────────────────────────────────────
        val coherenceHistory = remember(session.coherenceHistoryJson) {
            parseCoherenceHistory(session.coherenceHistoryJson)
        }
        if (coherenceHistory.size >= 2) {
            Text(
                "COHERENCE OVER TIME",
                style = MaterialTheme.typography.labelSmall,
                color = DetailAsh,
                letterSpacing = 2.sp,
                modifier = Modifier.padding(start = 4.dp)
            )
            CoherenceHistoryChart(
                values = coherenceHistory,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
            )
        }

        Spacer(Modifier.height(16.dp))
    }
}

// ── Zone bar ───────────────────────────────────────────────────────────────────

@Composable
private fun ZoneBar(label: String, seconds: Int, totalSeconds: Int, color: Color) {
    val fraction = seconds.toFloat() / totalSeconds
    val mins = seconds / 60
    val secs = seconds % 60
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.width(56.dp),
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(12.dp)
                .background(SurfaceVariant, RoundedCornerShape(6.dp))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .background(color, RoundedCornerShape(6.dp))
            )
        }
        Text(
            text = "%d:%02d".format(mins, secs),
            style = MaterialTheme.typography.labelSmall,
            color = TextDisabled,
            modifier = Modifier.width(40.dp),
            textAlign = TextAlign.End
        )
    }
}

// ── Metric chip ────────────────────────────────────────────────────────────────

@Composable
private fun MetricChip(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary,
            fontWeight = FontWeight.Bold
        )
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextDisabled)
    }
}

// ── Coherence history chart ────────────────────────────────────────────────────

@Composable
private fun CoherenceHistoryChart(values: List<Float>, modifier: Modifier = Modifier) {
    val lineColor = DetailBone
    val min = values.minOrNull() ?: 0f
    val max = (values.maxOrNull() ?: 1f).coerceAtLeast(min + 0.1f)
    val yPad = (max - min) * 0.1f

    var tappedIndex by remember { mutableStateOf<Int?>(null) }
    var chartWidthPx by remember { mutableFloatStateOf(0f) }

    Box(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(values) {
                    detectTapGestures { offset ->
                        val w = size.width.toFloat()
                        val frac = (offset.x / w).coerceIn(0f, 1f)
                        val idx = (frac * (values.size - 1)).toInt().coerceIn(0, values.size - 1)
                        tappedIndex = if (tappedIndex == idx) null else idx
                        chartWidthPx = w
                    }
                }
        ) {
            val w = size.width
            val h = size.height
            val yMin = min - yPad
            val yMax = max + yPad
            val yRange = (yMax - yMin).coerceAtLeast(0.001f)
            val xStep = w / (values.size - 1).toFloat()

        fun xOf(i: Int) = i * xStep
        fun yOf(v: Float) = h - ((v - yMin) / yRange * h).coerceIn(0f, h)

        // Zone reference lines
        listOf(1f, 3f).forEach { threshold ->
            if (threshold in yMin..yMax) {
                val y = yOf(threshold)
                drawLine(
                    color = DetailAsh.copy(alpha = 0.3f),
                    start = Offset(0f, y),
                    end = Offset(w, y),
                    strokeWidth = 1f
                )
            }
        }

        // Line
        val path = Path().apply {
            moveTo(xOf(0), yOf(values[0]))
            for (i in 1 until values.size) {
                lineTo(xOf(i), yOf(values[i]))
            }
        }
        drawPath(path, color = lineColor, style = Stroke(width = 2.5f, cap = StrokeCap.Round))

        // Latest dot
        val lastX = xOf(values.size - 1)
        val lastY = yOf(values.last())
        drawCircle(color = lineColor, radius = 5f, center = Offset(lastX, lastY))
        drawCircle(color = BackgroundDark, radius = 2.5f, center = Offset(lastX, lastY))

        // Tapped point indicator
        tappedIndex?.let { idx ->
            val cx = xOf(idx)
            val cy = yOf(values[idx])
            drawLine(
                DetailAsh.copy(alpha = 0.4f),
                Offset(cx, 0f),
                Offset(cx, h),
                strokeWidth = 1f
            )
            drawCircle(lineColor, radius = 6f, center = Offset(cx, cy))
            drawCircle(BackgroundDark, radius = 3f, center = Offset(cx, cy))
        }
    }

        // ── Info popup ─────────────────────────────────────────────────────
        tappedIndex?.let { idx ->
            val dataFrac = idx.toFloat() / (values.size - 1).coerceAtLeast(1).toFloat()
            val valStr   = "%.2f".format(values[idx])

            val popupOffsetX = with(LocalDensity.current) {
                val px = dataFrac * chartWidthPx
                (px - 30.dp.toPx()).toInt()
            }

            Popup(
                alignment = Alignment.TopStart,
                offset    = IntOffset(popupOffsetX, with(LocalDensity.current) { (-4).dp.roundToPx() }),
                onDismissRequest = { tappedIndex = null },
                properties = PopupProperties(focusable = false)
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = SurfaceDark,
                    shadowElevation = 4.dp
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            valStr,
                            style = MaterialTheme.typography.labelMedium,
                            color = lineColor,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

// ── JSON parser ────────────────────────────────────────────────────────────────

private fun parseCoherenceHistory(json: String): List<Float> {
    if (json.isBlank() || json == "[]") return emptyList()
    return try {
        val arr = JSONArray(json)
        (0 until arr.length()).map { arr.getDouble(it).toFloat() }
    } catch (_: Exception) {
        emptyList()
    }
}
