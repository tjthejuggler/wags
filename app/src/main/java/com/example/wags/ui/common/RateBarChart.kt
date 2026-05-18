package com.example.wags.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

// ── Palette (matches RateRecommendationScreen) ──────────────────────────────
private val ChartBgDark   = Color(0xFF0A0A0A)
private val ChartBgMid    = Color(0xFF1C1C1C)
private val BarDefault     = Color(0xFF4A90D9)
private val BarHighlighted = Color(0xFFD4AF37)  // RecGold
private val LabelColor     = Color(0xFFB0B0B0)  // RecSilver
private val ValueColor     = Color(0xFFE8E8E8)  // RecBone
private val GridColor      = Color(0xFF2A2A2A)
private val TooltipBg      = Color(0xFF2A2A2A)

/**
 * A single bar entry for the chart.
 */
data class BarEntry(
    val label: String,
    val value: Float,
    val isHighlighted: Boolean = false
)

/**
 * Vertical bar chart for comparing rate buckets.
 *
 * @param title    Chart title displayed above the chart.
 * @param entries  Bar data sorted in the desired X-axis order.
 * @param maxValue Optional ceiling for the Y-axis (auto-detected if null).
 * @param modifier Standard Compose modifier.
 */
@Composable
fun RateBarChart(
    title: String,
    entries: List<BarEntry>,
    maxValue: Float? = null,
    modifier: Modifier = Modifier
) {
    if (entries.isEmpty()) return

    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp)
    val valueStyle = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp)
    val titleStyle = MaterialTheme.typography.labelMedium
    val density = LocalDensity.current

    val yMax = maxValue ?: (entries.maxOf { it.value } * 1.15f).coerceAtLeast(0.01f)

    var selectedBar by remember { mutableIntStateOf(-1) }
    var canvasSize by remember { mutableStateOf(Size.Zero) }

    // Horizontal scroll when many bars would be too narrow
    val needsScroll = entries.size > 15
    val scrollState = rememberScrollState()
    val minChartWidthDp = if (needsScroll) (entries.size * 32).dp + 48.dp else 0.dp

    // Layout constants — padBottom increased from 24f to 40f for x-axis labels
    val padLeft = 36f
    val padRight = 8f
    val padTop = 8f
    val padBottom = 40f

    // Pre-compute bar hit-test rects whenever canvas size or entries change
    val barLayout = remember(canvasSize, entries) {
        if (canvasSize == Size.Zero || entries.isEmpty()) return@remember emptyList<Rect>()
        val chartW = canvasSize.width - padLeft - padRight
        val chartH = canvasSize.height - padTop - padBottom
        val barCount = entries.size
        val totalGap = chartW * 0.2f
        val gapWidth = if (barCount > 1) totalGap / (barCount + 1) else totalGap / 2f
        val barWidth = if (barCount > 0) (chartW - gapWidth * (barCount + 1)) / barCount else 0f
        entries.mapIndexed { i, _ ->
            val x = padLeft + gapWidth + i * (barWidth + gapWidth)
            Rect(x, padTop, x + barWidth, padTop + chartH)
        }
    }

    Column(modifier = modifier) {
        // Title
        Text(
            text = title,
            style = titleStyle,
            color = LabelColor,
            letterSpacing = 1.5.sp,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Box(modifier = Modifier
            .fillMaxWidth()
            .then(if (needsScroll) Modifier.horizontalScroll(scrollState) else Modifier)
        ) {
            Canvas(
                modifier = Modifier
                    .then(if (needsScroll) Modifier.width(minChartWidthDp) else Modifier.fillMaxWidth())
                    .height(200.dp)          // increased from 160.dp
                    .clip(RoundedCornerShape(8.dp))
                    .onSizeChanged { canvasSize = Size(it.width.toFloat(), it.height.toFloat()) }
                    .pointerInput(entries) {
                        detectTapGestures { offset ->
                            val tapped = barLayout.indexOfFirst { it.contains(offset) }
                            selectedBar = if (tapped == selectedBar) -1 else tapped
                        }
                    }
            ) {
                val w = size.width
                val h = size.height
                val chartW = w - padLeft - padRight
                val chartH = h - padTop - padBottom

                // Background
                drawRect(Brush.verticalGradient(listOf(ChartBgDark, ChartBgMid)), size = Size(w, h))

                // Horizontal grid lines (3 lines)
                for (i in 0..3) {
                    val y = padTop + chartH * i / 3f
                    drawLine(GridColor, Offset(padLeft, y), Offset(w - padRight, y), strokeWidth = 1f)
                    // Y-axis label
                    val gridVal = yMax * (1f - i / 3f)
                    val gridText = if (yMax <= 1f) "%.2f".format(gridVal) else "%.1f".format(gridVal)
                    val textLayout = textMeasurer.measure(gridText, style = valueStyle.copy(color = LabelColor))
                    drawText(
                        textLayout,
                        color = LabelColor,
                        topLeft = Offset(0f, y - textLayout.size.height / 2f)
                    )
                }

                // Bars
                val barCount = entries.size
                val totalGap = chartW * 0.2f  // 20% of chart width for gaps
                val gapWidth = if (barCount > 1) totalGap / (barCount + 1) else totalGap / 2f
                val barWidth = if (barCount > 0) (chartW - gapWidth * (barCount + 1)) / barCount else 0f

                entries.forEachIndexed { i, entry ->
                    val x = padLeft + gapWidth + i * (barWidth + gapWidth)
                    val barH = if (yMax > 0f) (entry.value / yMax) * chartH else 0f
                    val y = padTop + chartH - barH

                    val barColor = if (entry.isHighlighted) BarHighlighted else BarDefault

                    // Bar with rounded top corners
                    drawRoundRect(
                        color = barColor,
                        topLeft = Offset(x, y),
                        size = Size(barWidth, barH),
                        cornerRadius = CornerRadius(4f, 4f)
                    )

                    // Glow for highlighted bar
                    if (entry.isHighlighted) {
                        drawRoundRect(
                            color = BarHighlighted.copy(alpha = 0.25f),
                            topLeft = Offset(x - 2f, y - 2f),
                            size = Size(barWidth + 4f, barH + 4f),
                            cornerRadius = CornerRadius(6f, 6f)
                        )
                    }

                    // Selected bar highlight
                    if (i == selectedBar) {
                        drawRoundRect(
                            color = Color.White.copy(alpha = 0.18f),
                            topLeft = Offset(x - 1f, y - 1f),
                            size = Size(barWidth + 2f, barH + 2f),
                            cornerRadius = CornerRadius(5f, 5f)
                        )
                    }

                    // Value label above bar
                    val valText = if (yMax <= 1f) "%.2f".format(entry.value) else "%.1f".format(entry.value)
                    val valLayout = textMeasurer.measure(valText, style = valueStyle.copy(color = ValueColor))
                    drawText(
                        valLayout,
                        color = ValueColor,
                        topLeft = Offset(
                            x + barWidth / 2f - valLayout.size.width / 2f,
                            y - valLayout.size.height - 2f
                        )
                    )

                    // X-axis label below bar — offset increased from 4f to 6f
                    val xLayout = textMeasurer.measure(entry.label, style = labelStyle.copy(color = LabelColor))
                    drawText(
                        xLayout,
                        color = LabelColor,
                        topLeft = Offset(
                            x + barWidth / 2f - xLayout.size.width / 2f,
                            padTop + chartH + 6f
                        )
                    )
                }
            }

            // Tooltip popup for the selected bar
            if (selectedBar in barLayout.indices && selectedBar in entries.indices) {
                val entry = entries[selectedBar]
                val rect = barLayout[selectedBar]
                val barCenterX = rect.left + rect.width / 2
                val tooltipWidthPx: Float = with(density) { 120.dp.toPx() }
                val tooltipHeightPx: Float = with(density) { 60.dp.toPx() }

                // Position above the bar; fall back below if not enough room
                val tooltipY: Int = if (rect.top > tooltipHeightPx + 8f) {
                    (rect.top - tooltipHeightPx - 8f).toInt()
                } else {
                    (rect.bottom + 8f).toInt()
                }

                val scrollOffsetPx = if (needsScroll) scrollState.value else 0
                val tooltipX: Int = (barCenterX - tooltipWidthPx / 2f - scrollOffsetPx)
                    .toInt()
                    .coerceIn(0, (canvasSize.width - tooltipWidthPx).toInt().coerceAtLeast(0))

                Popup(
                    alignment = Alignment.TopStart,
                    offset = IntOffset(tooltipX, tooltipY),
                    properties = PopupProperties(clippingEnabled = false)
                ) {
                    Card(
                        modifier = Modifier.width(120.dp),
                        colors = CardDefaults.cardColors(containerColor = TooltipBg),
                        shape = RoundedCornerShape(8.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "${entry.label} BPM",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (entry.isHighlighted) BarHighlighted else ValueColor,
                                fontWeight = FontWeight.Bold
                            )
                            val displayValue = if (entry.value == entry.value.toInt().toFloat()) {
                                entry.value.toInt().toString()
                            } else if (yMax <= 1f) {
                                "%.2f".format(entry.value)
                            } else {
                                "%.1f".format(entry.value)
                            }
                            Text(
                                text = displayValue,
                                style = MaterialTheme.typography.bodyMedium,
                                color = ValueColor
                            )
                            if (entry.isHighlighted) {
                                Text(
                                    text = "★ Recommended",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = BarHighlighted
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
