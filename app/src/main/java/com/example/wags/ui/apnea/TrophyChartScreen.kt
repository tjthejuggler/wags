package com.example.wags.ui.apnea

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.example.wags.ui.common.LiveSensorActionsNav
import com.example.wags.ui.theme.*
import java.text.SimpleDateFormat
import java.time.ZoneId
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrophyChartScreen(
    navController: NavController,
    viewModel: TrophyChartViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Force landscape
    val activity = LocalContext.current as? Activity
    DisposableEffect(Unit) {
        val original = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            activity?.requestedOrientation =
                original ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // Settings popup
    if (state.showSettingsPopup) {
        TrophyChartSettingsDialog(
            includeProgressiveO2 = state.includeProgressiveO2,
            includeMinBreath = state.includeMinBreath,
            onSetProgressiveO2 = { viewModel.setIncludeProgressiveO2(it) },
            onSetMinBreath = { viewModel.setIncludeMinBreath(it) },
            onDismiss = { viewModel.toggleSettingsPopup() }
        )
    }

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "Trophy Chart",
                            style = MaterialTheme.typography.titleSmall,
                            color = TextPrimary
                        )
                        Text(
                            if (state.showTotal) "Total trophies per day" else "Best single record per day",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Text("←", style = MaterialTheme.typography.headlineMedium, color = TextSecondary)
                    }
                },
                actions = {
                    LiveSensorActionsNav(navController)
                    // Total / Max toggle
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(end = 4.dp)
                    ) {
                        Text(
                            "Total",
                            fontSize = 12.sp,
                            color = if (state.showTotal) TextPrimary else TextSecondary,
                            modifier = Modifier.padding(end = 4.dp)
                        )
                        Switch(
                            checked = state.showTotal,
                            onCheckedChange = { viewModel.toggleShowTotal() },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = TextPrimary,
                                checkedTrackColor = SurfaceVariant,
                                uncheckedThumbColor = TextSecondary,
                                uncheckedTrackColor = SurfaceVariant
                            ),
                            modifier = Modifier.height(24.dp)
                        )
                    }
                    // Settings button
                    IconButton(onClick = { viewModel.toggleSettingsPopup() }) {
                        Text("⚙", style = MaterialTheme.typography.titleMedium, color = TextSecondary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark)
            )
        }
    ) { padding ->
        if (state.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator(color = TextSecondary) }
        } else if (state.days.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text("No trophy records yet", color = TextSecondary)
            }
        } else {
            TrophyBarChart(
                days = state.days,
                showTotal = state.showTotal,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Settings popup
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun TrophyChartSettingsDialog(
    includeProgressiveO2: Boolean,
    includeMinBreath: Boolean,
    onSetProgressiveO2: (Boolean) -> Unit,
    onSetMinBreath: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
        title = {
            Text("Include Drills", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Free Hold is always included. Select additional drills that award trophies:",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Progressive O₂", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                    Switch(
                        checked = includeProgressiveO2,
                        onCheckedChange = onSetProgressiveO2
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Min Breath", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                    Switch(
                        checked = includeMinBreath,
                        onCheckedChange = onSetMinBreath
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done", color = TextPrimary)
            }
        }
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Bar chart composable with zoom & pan
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun TrophyBarChart(
    days: List<TrophyChartDay>,
    showTotal: Boolean,
    modifier: Modifier = Modifier
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(days) {
        scale = 1f
        offsetX = 0f
    }

    val leftPad = 40f
    val bottomPad = 40f
    val topPad = 16f
    val rightPad = 16f

    val barColor = TextPrimary.copy(alpha = 0.85f)
    val gridColor = TextDisabled.copy(alpha = 0.3f)
    val dashEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))

    val paint = remember {
        android.graphics.Paint().apply {
            isAntiAlias = true
            textSize = 26f
            color = android.graphics.Color.argb(180, 144, 144, 144)
        }
    }

    Canvas(
        modifier = modifier
            .background(BackgroundDark)
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val newScale = (scale * zoom).coerceIn(1f, 20f)
                    val chartW = size.width - leftPad - rightPad
                    val maxOffset = chartW * (newScale - 1f)
                    val newOffset = (offsetX + pan.x).coerceIn(-maxOffset, 0f)
                    scale = newScale
                    offsetX = newOffset
                }
            }
    ) {
        val w = size.width
        val h = size.height
        val chartW = w - leftPad - rightPad
        val chartH = h - topPad - bottomPad

        val values = days.map { if (showTotal) it.totalTrophies else it.maxTrophies }
        val maxVal = values.maxOrNull()?.toFloat() ?: 1f
        val yMax = (maxVal * 1.15f).coerceAtLeast(1f)

        // ── Y-axis grid lines ────────────────────────────────────────────
        val yTicks = computeTrophyYTicks(0f, yMax)
        for (tick in yTicks) {
            val y = topPad + chartH * (1f - tick / yMax)
            drawLine(gridColor, Offset(leftPad, y), Offset(w - rightPad, y),
                strokeWidth = 1f, pathEffect = dashEffect)
            drawContext.canvas.nativeCanvas.drawText(
                tick.toInt().toString(),
                2f, y + 9f, paint
            )
        }

        // ── Bars ─────────────────────────────────────────────────────────
        val totalBars = days.size
        val scaledBarSlot = (chartW * scale) / totalBars.toFloat()
        val barW = (scaledBarSlot * 0.7f).coerceAtLeast(4f)
        val barGap = scaledBarSlot - barW

        val zone = ZoneId.systemDefault()
        val fmtLabel = SimpleDateFormat("MMM d", Locale.getDefault())

        for (i in days.indices) {
            val value = values[i]
            val barX = leftPad + offsetX + i * scaledBarSlot + barGap / 2f
            if (barX + barW < leftPad || barX > w - rightPad) continue

            val barH = chartH * (value.toFloat() / yMax)
            val barTop = topPad + chartH - barH

            val clampedLeft = barX.coerceAtLeast(leftPad)
            val clampedRight = (barX + barW).coerceAtMost(w - rightPad)
            if (clampedRight <= clampedLeft) continue

            drawRect(
                color = barColor,
                topLeft = Offset(clampedLeft, barTop),
                size = Size(clampedRight - clampedLeft, barH)
            )

            // Value label above bar (only when bar is wide enough)
            if (scaledBarSlot > 30f && value > 0) {
                drawContext.canvas.nativeCanvas.drawText(
                    value.toString(),
                    barX + scaledBarSlot / 2f - 8f,
                    (barTop - 4f).coerceAtLeast(topPad + 12f),
                    paint
                )
            }

            // X-axis date label (only when bars are wide enough)
            if (scaledBarSlot > 50f) {
                val epochMs = days[i].date.atStartOfDay(zone).toInstant().toEpochMilli()
                val label = fmtLabel.format(Date(epochMs))
                drawContext.canvas.nativeCanvas.drawText(
                    label,
                    barX + scaledBarSlot / 2f - 20f,
                    h - 4f,
                    paint
                )
            }
        }

        // ── X-axis date labels when bars are narrow (show every Nth) ─────
        if (scaledBarSlot <= 50f && totalBars > 0) {
            val step = when {
                scaledBarSlot > 20f -> 7
                scaledBarSlot > 8f  -> 14
                else                -> 30
            }
            for (i in days.indices step step) {
                val barX = leftPad + offsetX + i * scaledBarSlot + scaledBarSlot / 2f
                if (barX < leftPad || barX > w - rightPad) continue
                val epochMs = days[i].date.atStartOfDay(zone).toInstant().toEpochMilli()
                val label = fmtLabel.format(Date(epochMs))
                drawContext.canvas.nativeCanvas.drawText(label, barX - 20f, h - 4f, paint)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Y-axis tick computation for trophy counts
// ─────────────────────────────────────────────────────────────────────────────

private fun computeTrophyYTicks(minVal: Float, maxVal: Float): List<Float> {
    val range = maxVal - minVal
    if (range <= 0f) return listOf(0f, 1f)
    val rawStep = range / 5f
    val step = trophyNiceStep(rawStep).toFloat()
    val ticks = mutableListOf<Float>()
    var v = 0f
    while (v <= maxVal) {
        ticks.add(v)
        v += step
    }
    return ticks
}

private fun trophyNiceStep(raw: Float): Double {
    if (raw <= 0) return 1.0
    val exp = kotlin.math.floor(kotlin.math.log10(raw.toDouble()))
    val base = Math.pow(10.0, exp)
    val frac = raw.toDouble() / base
    val nice = when {
        frac <= 1.5 -> 1.0
        frac <= 3.5 -> 2.0
        frac <= 7.5 -> 5.0
        else -> 10.0
    }
    return (nice * base).coerceAtLeast(1.0)
}
