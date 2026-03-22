package com.example.wags.ui.apnea

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.example.wags.data.db.entity.ApneaRecordEntity
import com.example.wags.data.db.entity.FreeHoldTelemetryEntity
import com.example.wags.domain.model.PersonalBestCategory
import com.example.wags.domain.model.PrepType
import com.example.wags.domain.model.RecordPbBadge
import com.example.wags.domain.model.TimeOfDay
import com.example.wags.domain.model.trophyEmojis
import com.example.wags.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

private val SpO2Blue = Color(0xFF42A5F5)

// Physiological bounds for display-layer filtering (mirrors PhysiologicalBounds in FreeHoldActiveScreen)
// HR: 20–250 bpm | SpO2: 1–100% (0 = no-signal glitch; real extreme dives can go very low)
private const val DISPLAY_HR_MIN   = 20f
private const val DISPLAY_HR_MAX   = 250f
private const val DISPLAY_SPO2_MIN = 1f
private const val DISPLAY_SPO2_MAX = 100f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApneaRecordDetailScreen(
    navController: NavController,
    viewModel: ApneaRecordDetailViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showDeleteDialog by remember { mutableStateOf(false) }

    // Pop back when deletion completes
    LaunchedEffect(Unit) {
        viewModel.deleted.collect {
            navController.popBackStack()
        }
    }

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = { Text("Hold Detail") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Text("←", style = MaterialTheme.typography.headlineMedium, color = EcgCyan)
                    }
                },
                actions = {
                    if (!state.isLoading && !state.notFound) {
                        // Edit button
                        IconButton(onClick = { viewModel.openEditSheet() }) {
                            Icon(
                                imageVector = Icons.Outlined.Edit,
                                contentDescription = "Edit record settings",
                                tint = EcgCyan
                            )
                        }
                        // Delete button
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(
                                imageVector = Icons.Outlined.Delete,
                                contentDescription = "Delete record",
                                tint = ButtonDanger
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark)
            )
        }
    ) { padding ->
        when {
            state.isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator(color = EcgCyan) }
            }
            state.notFound -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Record not found.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextSecondary
                    )
                }
            }
            state.record != null -> {
                RecordDetailContent(
                    record = state.record!!,
                    telemetry = state.telemetry,
                    pbBadges = state.pbBadges,
                    modifier = Modifier.padding(padding)
                )
            }
        }
    }

    // ── Delete confirmation dialog ─────────────────────────────────────────────
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Record") },
            text = {
                Text(
                    "Are you sure you want to permanently delete this hold record? " +
                    "This cannot be undone."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        viewModel.deleteRecord()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = ButtonDanger)
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            }
        )
    }

    // ── Edit bottom sheet ──────────────────────────────────────────────────────
    if (state.showEditSheet) {
        EditRecordSheet(
            lungVolume  = state.editLungVolume,
            prepType    = state.editPrepType,
            timeOfDay   = state.editTimeOfDay,
            onLungVolumeChange = { viewModel.setEditLungVolume(it) },
            onPrepTypeChange   = { viewModel.setEditPrepType(it) },
            onTimeOfDayChange  = { viewModel.setEditTimeOfDay(it) },
            onSave    = { viewModel.saveEdits() },
            onDismiss = { viewModel.closeEditSheet() }
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Edit bottom sheet
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditRecordSheet(
    lungVolume: String,
    prepType: PrepType,
    timeOfDay: TimeOfDay,
    onLungVolumeChange: (String) -> Unit,
    onPrepTypeChange: (PrepType) -> Unit,
    onTimeOfDayChange: (TimeOfDay) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
        tonalElevation = 4.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Text(
                "Edit Record Settings",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )

            // ── Lung Volume ───────────────────────────────────────────────────
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Lung Volume", style = MaterialTheme.typography.labelLarge, color = TextSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("FULL", "HALF", "EMPTY").forEach { vol ->
                        val isSelected = lungVolume == vol
                        FilterChip(
                            selected = isSelected,
                            onClick  = { onLungVolumeChange(vol) },
                            label    = {
                                Text(
                                    vol.lowercase().replaceFirstChar { it.uppercase() },
                                    color = if (isSelected) Color.Black else MaterialTheme.colorScheme.onSurface
                                )
                            },
                            colors   = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = EcgCyan,
                                selectedLabelColor     = Color.Black,
                                labelColor             = MaterialTheme.colorScheme.onSurface
                            )
                        )
                    }
                }
            }

            // ── Prep Type ─────────────────────────────────────────────────────
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Prep Type", style = MaterialTheme.typography.labelLarge, color = TextSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrepType.entries.forEach { pt ->
                        val isSelected = prepType == pt
                        FilterChip(
                            selected = isSelected,
                            onClick  = { onPrepTypeChange(pt) },
                            label    = {
                                Text(
                                    pt.displayName(),
                                    color = if (isSelected) Color.Black else MaterialTheme.colorScheme.onSurface
                                )
                            },
                            colors   = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = EcgCyan,
                                selectedLabelColor     = Color.Black,
                                labelColor             = MaterialTheme.colorScheme.onSurface
                            )
                        )
                    }
                }
            }

            // ── Time of Day ───────────────────────────────────────────────────
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Time of Day", style = MaterialTheme.typography.labelLarge, color = TextSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TimeOfDay.entries.forEach { tod ->
                        val isSelected = timeOfDay == tod
                        FilterChip(
                            selected = isSelected,
                            onClick  = { onTimeOfDayChange(tod) },
                            label    = {
                                Text(
                                    tod.name.lowercase().replaceFirstChar { it.uppercase() },
                                    color = if (isSelected) Color.Black else MaterialTheme.colorScheme.onSurface
                                )
                            },
                            colors   = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = EcgCyan,
                                selectedLabelColor     = Color.Black,
                                labelColor             = MaterialTheme.colorScheme.onSurface
                            )
                        )
                    }
                }
            }

            // ── Action buttons ────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                ) { Text("Cancel") }

                Button(
                    onClick = onSave,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = EcgCyan, contentColor = BackgroundDark)
                ) { Text("Save", fontWeight = FontWeight.Bold) }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Main content
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun RecordDetailContent(
    record: ApneaRecordEntity,
    telemetry: List<FreeHoldTelemetryEntity>,
    pbBadges: List<RecordPbBadge>,
    modifier: Modifier = Modifier
) {
    val dateStr = remember(record.timestamp) {
        SimpleDateFormat("EEEE, MMM d yyyy  HH:mm:ss", Locale.getDefault())
            .format(Date(record.timestamp))
    }

    // Build indexed samples paired with elapsed-ms so the chart can place time markers correctly.
    // We use the record's own timestamp as t=0 and map each telemetry row to elapsed ms.
    val recordStartMs = record.timestamp

    val hrSamples = remember(telemetry) {
        telemetry.mapNotNull { row ->
            val hr = row.heartRateBpm?.toFloat() ?: return@mapNotNull null
            if (hr !in DISPLAY_HR_MIN..DISPLAY_HR_MAX) return@mapNotNull null
            Pair(hr, row.timestampMs - recordStartMs)
        }
    }
    val spO2Samples = remember(telemetry) {
        telemetry.mapNotNull { row ->
            val spo2 = row.spO2?.toFloat() ?: return@mapNotNull null
            if (spo2 !in DISPLAY_SPO2_MIN..DISPLAY_SPO2_MAX) return@mapNotNull null
            Pair(spo2, row.timestampMs - recordStartMs)
        }
    }

    val hrValues   = remember(hrSamples)   { hrSamples.map   { it.first } }
    val spO2Values = remember(spO2Samples) { spO2Samples.map { it.first } }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ── Summary ───────────────────────────────────────────────────────
        Card(colors = CardDefaults.cardColors(containerColor = SurfaceVariant)) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Summary", style = MaterialTheme.typography.titleLarge)
                DetailRow(label = "Date", value = dateStr)
                DetailRow(
                    label = "Duration",
                    value = formatMsDetail(record.durationMs),
                    valueColor = EcgCyan,
                    valueBold = true
                )
                DetailRow(
                    label = "Lung Volume",
                    value = record.lungVolume.lowercase().replaceFirstChar { it.uppercase() }
                )
                DetailRow(
                    label = "Prep Type",
                    value = record.prepType.lowercase().replace('_', ' ')
                        .replaceFirstChar { it.uppercase() }
                )
                DetailRow(label = "Time of Day",
                    value = record.timeOfDay.lowercase().replaceFirstChar { it.uppercase() }
                )
                DetailRow(label = "Type", value = record.tableType ?: "Free Hold")
                DetailRow(
                    label = "HR/SpO₂ Device",
                    value = record.hrDeviceId ?: "None recorded"
                )
                record.firstContractionMs?.let { fcMs ->
                    DetailRow(
                        label = "First Contraction",
                        value = formatMsDetail(fcMs),
                        valueColor = Color(0xFFFF9800),
                        valueBold = true
                    )
                }

                // ── Personal Best badges ────────────────────────────────────
                if (pbBadges.isNotEmpty()) {
                    HorizontalDivider(
                        color = Color.White.copy(alpha = 0.12f),
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                    Text(
                        "Personal Best",
                        style = MaterialTheme.typography.titleMedium,
                        color = EcgCyan
                    )
                    pbBadges.forEach { badge ->
                        val trophies = badge.category.trophyEmojis()
                        val status = if (badge.isCurrent) "Current" else "Former"
                        val statusColor = if (badge.isCurrent) EcgCyan else TextSecondary
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "$trophies ${badge.description}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (badge.isCurrent) Color.White else TextSecondary
                            )
                            Text(
                                status,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = statusColor
                            )
                        }
                    }
                }
            }
        }

        // ── Heart Rate chart ──────────────────────────────────────────────
        Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Heart Rate", style = MaterialTheme.typography.titleLarge)

                if (hrValues.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        StatBox(
                            label = "Start",
                            value = "${hrValues.first().toInt()}",
                            color = Color.White
                        )
                        StatBox(
                            label = "Min",
                            value = "${hrValues.min().toInt()}",
                            color = EcgCyan
                        )
                        StatBox(
                            label = "Avg",
                            value = "${hrValues.average().toInt()}",
                            color = Color.White
                        )
                        StatBox(
                            label = "Max",
                            value = "${hrValues.max().toInt()}",
                            color = ApneaHold
                        )
                        StatBox(
                            label = "End",
                            value = "${hrValues.last().toInt()}",
                            color = Color.White
                        )
                    }
                    LineChart(
                        samples = hrValues,
                        lineColor = EcgCyan,
                        durationMs = record.durationMs,
                        firstContractionMs = record.firstContractionMs,
                        showYLabels = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                    )
                    Text(
                        "HR over the hold (${hrValues.size} beats)",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                } else if (record.minHrBpm > 0f || record.maxHrBpm > 0f) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        StatBox(label = "Min", value = "${record.minHrBpm.toInt()} bpm", color = EcgCyan)
                        StatBox(label = "Max", value = "${record.maxHrBpm.toInt()} bpm", color = ApneaHold)
                    }
                    Text(
                        "Per-beat data not available for this record.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                } else {
                    Text(
                        "No heart rate data recorded for this hold.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }
        }

        // ── SpO2 chart ────────────────────────────────────────────────────
        Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("SpO₂", style = MaterialTheme.typography.titleLarge)

                if (spO2Values.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        StatBox(
                            label = "Start",
                            value = "${spO2Values.first().toInt()}",
                            color = Color.White
                        )
                        StatBox(
                            label = "Min",
                            value = "${spO2Values.min().toInt()}",
                            color = SpO2Blue
                        )
                        StatBox(
                            label = "Avg",
                            value = "${spO2Values.average().toInt()}",
                            color = Color.White
                        )
                        StatBox(
                            label = "Max",
                            value = "${spO2Values.max().toInt()}",
                            color = Color.White
                        )
                        StatBox(
                            label = "End",
                            value = "${spO2Values.last().toInt()}",
                            color = Color.White
                        )
                    }
                    LineChart(
                        samples = spO2Values,
                        lineColor = SpO2Blue,
                        yMin = 70f,
                        yMax = 100f,
                        durationMs = record.durationMs,
                        firstContractionMs = record.firstContractionMs,
                        showYLabels = false,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp)
                    )
                    Text(
                        "SpO₂ over the hold (${spO2Values.size} samples)",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                } else {
                    Text(
                        "No SpO₂ data recorded. Connect a pulse oximeter during your hold to capture oxygen saturation.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Line chart composable
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun LineChart(
    samples: List<Float>,
    lineColor: Color,
    modifier: Modifier = Modifier,
    yMin: Float = samples.minOrNull() ?: 0f,
    yMax: Float = samples.maxOrNull() ?: 1f,
    /** Total hold duration in ms — used to place time-axis markers. */
    durationMs: Long = 0L,
    /** Elapsed ms of first contraction — draws a dim vertical line when non-null. */
    firstContractionMs: Long? = null,
    /** When true, draws 4 evenly-spaced HR value labels on the left Y-axis. */
    showYLabels: Boolean = false
) {
    if (samples.size < 2) return

    val density = LocalDensity.current
    // Reserve left margin for Y labels, bottom margin for time labels
    val leftPadPx  = if (showYLabels) with(density) { 34.dp.toPx() } else with(density) { 4.dp.toPx() }
    val bottomPadPx = with(density) { 16.dp.toPx() }
    val topPadPx    = with(density) { 4.dp.toPx() }
    val rightPadPx  = with(density) { 4.dp.toPx() }

    val labelTextSizePx = with(density) { 9.sp.toPx() }
    val labelColor      = Color.White.copy(alpha = 0.45f)
    val labelArgb       = labelColor.toArgb()

    val yRange = (yMax - yMin).coerceAtLeast(1f)

    Canvas(modifier = modifier) {
        val totalW = size.width
        val totalH = size.height

        // Plot area bounds
        val plotLeft   = leftPadPx
        val plotRight  = totalW - rightPadPx
        val plotTop    = topPadPx
        val plotBottom = totalH - bottomPadPx
        val plotW      = (plotRight - plotLeft).coerceAtLeast(1f)
        val plotH      = (plotBottom - plotTop).coerceAtLeast(1f)

        val stepX = plotW / (samples.size - 1).toFloat()

        // ── Subtle horizontal grid lines ──────────────────────────────────
        listOf(0.25f, 0.5f, 0.75f).forEach { frac ->
            val y = plotTop + plotH * (1f - frac)
            drawLine(
                color = Color.White.copy(alpha = 0.07f),
                start = Offset(plotLeft, y),
                end   = Offset(plotRight, y),
                strokeWidth = 1f
            )
        }

        // ── First-contraction vertical line ───────────────────────────────
        if (firstContractionMs != null && durationMs > 0L) {
            val fcFrac = (firstContractionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
            val fcX    = plotLeft + fcFrac * plotW
            drawLine(
                color       = lineColor.copy(alpha = 0.30f),
                start       = Offset(fcX, plotTop),
                end         = Offset(fcX, plotBottom),
                strokeWidth = 1.5f
            )
        }

        // ── Main data line ────────────────────────────────────────────────
        val path = Path()
        samples.forEachIndexed { i, value ->
            val x = plotLeft + i * stepX
            val y = plotTop + plotH * (1f - ((value - yMin) / yRange).coerceIn(0f, 1f))
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path  = path,
            color = lineColor,
            style = Stroke(width = 2.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        // End-point dots
        val firstY = plotTop + plotH * (1f - ((samples.first() - yMin) / yRange).coerceIn(0f, 1f))
        val lastY  = plotTop + plotH * (1f - ((samples.last()  - yMin) / yRange).coerceIn(0f, 1f))
        drawCircle(color = lineColor, radius = 5f, center = Offset(plotLeft,  firstY))
        drawCircle(color = lineColor, radius = 5f, center = Offset(plotRight, lastY))

        // ── Y-axis labels (HR only) ───────────────────────────────────────
        if (showYLabels) {
            val paint = Paint().apply {
                isAntiAlias = true
                textSize    = labelTextSizePx
                color       = labelArgb
                textAlign   = Paint.Align.RIGHT
            }
            // 4 evenly spaced labels: 0%, 33%, 66%, 100% of range
            for (step in 0..3) {
                val frac  = step / 3f
                val value = yMin + frac * yRange
                val y     = plotTop + plotH * (1f - frac)
                drawContext.canvas.nativeCanvas.drawText(
                    "${value.toInt()}",
                    plotLeft - with(density) { 3.dp.toPx() },
                    y + labelTextSizePx / 2f,
                    paint
                )
            }
        }

        // ── X-axis time labels ────────────────────────────────────────────
        if (durationMs > 0L) {
            val paint = Paint().apply {
                isAntiAlias = true
                textSize    = labelTextSizePx
                color       = labelArgb
                textAlign   = Paint.Align.CENTER
            }
            val totalMinutes = (durationMs / 60_000L).toInt()
            for (min in 1..totalMinutes) {
                val frac = (min * 60_000L).toFloat() / durationMs.toFloat()
                if (frac > 1f) break
                val x = plotLeft + frac * plotW
                // Tiny tick
                drawLine(
                    color       = labelColor,
                    start       = Offset(x, plotBottom),
                    end         = Offset(x, plotBottom + with(density) { 3.dp.toPx() }),
                    strokeWidth = 1f
                )
                drawContext.canvas.nativeCanvas.drawText(
                    "${min}m",
                    x,
                    totalH - with(density) { 1.dp.toPx() },
                    paint
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Small helpers
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun StatBox(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = MaterialTheme.typography.headlineSmall,
            color = color,
            fontWeight = FontWeight.Bold
        )
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
    }
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
    valueColor: Color = Color.Unspecified,
    valueBold: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        Text(
            value,
            style = if (valueBold)
                MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold)
            else
                MaterialTheme.typography.bodyLarge,
            color = if (valueColor == Color.Unspecified)
                MaterialTheme.colorScheme.onSurface
            else
                valueColor
        )
    }
}

private fun formatMsDetail(ms: Long): String {
    val totalSeconds = ms / 1000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    val centis = (ms % 1000L) / 10L
    return if (minutes > 0)
        "${minutes}m ${seconds}s ${centis}cs"
    else
        "${seconds}.${centis.toString().padStart(2, '0')}s"
}
