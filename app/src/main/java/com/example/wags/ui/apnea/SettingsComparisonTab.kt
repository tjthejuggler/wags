package com.example.wags.ui.apnea

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.wags.domain.usecase.apnea.ComparisonSessionType
import com.example.wags.domain.usecase.apnea.SettingsCategoryResult
import com.example.wags.domain.usecase.apnea.SettingsMasterRankEntry
import com.example.wags.domain.usecase.apnea.SettingsOptionResult
import com.example.wags.ui.theme.*
import java.time.format.DateTimeFormatter

// ── Tab content ────────────────────────────────────────────────────────────────

/**
 * "Settings" tab of the apnea History screen: compares hold-time performance
 * across every settings category (lung volume, prep, time of day, posture,
 * audio), with per-category ranked lists and a cross-category master ranking.
 *
 * Scoped by a session-type popup filter (any combination of free holds,
 * tables, drills) and a time window that can be stepped through history
 * (e.g. this month vs last month, last 3 months vs the 3 before those).
 */
@Composable
fun SettingsComparisonTabContent(viewModel: ApneaHistoryViewModel) {
    val state by viewModel.settingsComparison.collectAsStateWithLifecycle()
    var showTypeDialog by remember { mutableStateOf(false) }
    /** Bulk default for the lists below: raw averages or adjusted scores. */
    var scoreMode by rememberSaveable { mutableStateOf(false) }
    /** Per-list metric overrides — tapping a stat token re-ranks that one list. */
    var masterMetricOverride by rememberSaveable { mutableStateOf<String?>(null) }
    var hourMetricOverride by rememberSaveable { mutableStateOf<String?>(null) }
    var categoryMetricOverrides by rememberSaveable { mutableStateOf(mapOf<String, String>()) }

    fun parseMetric(name: String?): OptionMetric? =
        name?.let { v -> OptionMetric.entries.firstOrNull { it.name == v } }

    if (showTypeDialog) {
        SessionTypeFilterDialog(
            selected = state.sessionTypes,
            onApply = {
                viewModel.setComparisonSessionTypes(it)
                showTypeDialog = false
            },
            onDismiss = { showTypeDialog = false }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // ── Filter bar ────────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(SurfaceVariant)
                    .clickable { showTypeDialog = true }
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                val count = state.sessionTypes.size
                Text(
                    text = if (count == ComparisonSessionType.entries.size) "All session types"
                    else "$count session type${if (count == 1) "" else "s"}",
                    style = MaterialTheme.typography.labelMedium,
                    color = EcgCyan,
                    fontWeight = FontWeight.Bold
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(SurfaceVariant)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = state.period.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary
                )
            }
        }

        // ── Window length chips + step arrows ─────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ApneaChartTimePeriod.entries.forEach { period ->
                val isSelected = period == state.period
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isSelected) EcgCyan.copy(alpha = 0.25f) else SurfaceDark)
                        .clickable { viewModel.setComparisonPeriod(period) }
                        .padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = period.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSelected) EcgCyan else TextSecondary,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }

        val result = state.result
        when {
            state.isLoading || result == null -> Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator(color = EcgCyan) }

            result.totalHolds == 0 -> Card(colors = CardDefaults.cardColors(containerColor = SurfaceVariant)) {
                Text(
                    "No holds in this window for the selected session types.\n" +
                        "Try a longer window or enable more session types.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(20.dp)
                )
            }

            else -> {
                // ── Window navigation + summary ────────────────────────────
                if (state.period != ApneaChartTimePeriod.ALL) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "‹",
                            style = MaterialTheme.typography.headlineMedium,
                            color = if (result.canStepBack) EcgCyan else TextDisabled,
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable(enabled = result.canStepBack) { viewModel.stepComparisonBack() }
                                .padding(horizontal = 10.dp)
                        )
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "${result.windowStart?.format(SHORT_DATE)} – ${result.windowEnd?.format(SHORT_DATE)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "vs ${result.prevWindowStart?.format(SHORT_DATE)} – ${result.prevWindowEnd?.format(SHORT_DATE)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextDisabled
                            )
                        }
                        Text(
                            "›",
                            style = MaterialTheme.typography.headlineMedium,
                            color = if (result.canStepForward) EcgCyan else TextDisabled,
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable(enabled = result.canStepForward) { viewModel.stepComparisonForward() }
                                .padding(horizontal = 10.dp)
                        )
                    }
                }

                Text(
                    "${result.totalHolds} holds · avg ${formatHoldDuration(result.globalAvgMs)} overall",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextDisabled
                )

                // The master ranking keeps the adjusted score as its default metric;
                // every list below defaults to the toggle and can be overridden per list.
                val masterMetric = parseMetric(masterMetricOverride) ?: OptionMetric.SCORE
                val defaultMetric = if (scoreMode) OptionMetric.SCORE else OptionMetric.AVG
                val hourMetric = parseMetric(hourMetricOverride) ?: defaultMetric

                // ── Master ranking (across all categories) ─────────────────
                MasterRankingCard(
                    entries = result.masterRanking,
                    includeHours = state.includeHours,
                    onToggleHours = viewModel::setComparisonIncludeHours,
                    metric = masterMetric,
                    onMetricChange = { masterMetricOverride = it.name }
                )

                // ── Metric mode toggle: default metric for sections below ──
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Sections:",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextDisabled
                    )
                    MetricModeToggle(scoreMode = scoreMode, onChange = { enabled ->
                        scoreMode = enabled
                        masterMetricOverride = null
                        hourMetricOverride = null
                        categoryMetricOverrides = emptyMap()
                    })
                }

                // ── Per-category ranked lists ─────────────────────────────
                result.categories.forEach { category ->
                    val metric = parseMetric(categoryMetricOverrides[category.title]) ?: defaultMetric
                    CategoryCard(category, metric) { m ->
                        categoryMetricOverrides = categoryMetricOverrides + (category.title to m.name)
                    }
                }

                // ── Hour of day section (chart + ranked list) ─────────────
                result.hourCategory?.let { hourCat ->
                    HourOfDayCard(hourCat, hourMetric) { hourMetricOverride = it.name }
                }

                Text(
                    if (scoreMode)
                        "Score = each option's hold-time effect adjusted for every other setting, " +
                            "session type and hour of day (100 = average; 120 ≈ 20% longer all-else-equal). " +
                            "Δ compares this window to the previous one."
                    else
                        "Avg = raw average hold time in this window. " +
                            "Δ compares this window to the previous one.",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextDisabled
                )
            }
        }
    }
}

// ── Master ranking card ────────────────────────────────────────────────────────

@Composable
private fun MasterRankingCard(
    entries: List<SettingsMasterRankEntry>,
    includeHours: Boolean,
    onToggleHours: (Boolean) -> Unit,
    metric: OptionMetric,
    onMetricChange: (OptionMetric) -> Unit
) {
    // Re-rank by the selected metric; the first entry of each category in the
    // current order keeps the white-bordered chip.
    val ordered = remember(entries, metric) { entries.sortedByDescending { metric.value(it.option) } }
    val ranks = remember(entries, metric) {
        val byMetric = entries.sortedByDescending { metric.value(it.option) }
        entries.associate { e ->
            (e.categoryTitle to e.option.key) to
                (byMetric.indexOfFirst { it.option.key == e.option.key && it.categoryTitle == e.categoryTitle } + 1)
        }
    }
    val topOfCategory = remember(ordered) {
        val seen = mutableSetOf<String>()
        ordered.filter { seen.add(it.categoryTitle) }
            .map { it.categoryTitle to it.option.key }
            .toSet()
    }
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceVariant)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Master Ranking",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "All setting options across every category, ranked by ${metric.label}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextDisabled
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Hours", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                    Switch(
                        checked = includeHours,
                        onCheckedChange = onToggleHours,
                        colors = SwitchDefaults.colors(checkedTrackColor = EcgCyan)
                    )
                }
            }
            HorizontalDivider(color = SurfaceDark)
            ordered.forEach { entry ->
                MasterRankRow(
                    entry = entry,
                    rank = ranks.getValue(entry.categoryTitle to entry.option.key),
                    isTopOfCategory = (entry.categoryTitle to entry.option.key) in topOfCategory,
                    metric = metric,
                    onMetricChange = onMetricChange
                )
            }
        }
    }
}

@Composable
private fun MasterRankRow(
    entry: SettingsMasterRankEntry,
    rank: Int,
    isTopOfCategory: Boolean,
    metric: OptionMetric,
    onMetricChange: (OptionMetric) -> Unit
) {
    val opt = entry.option
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RankBadge(rank)
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    opt.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    entry.categoryTitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isTopOfCategory) TextPrimary else TextDisabled,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(SurfaceDark)
                        .then(
                            if (isTopOfCategory)
                                Modifier.border(1.dp, TextPrimary, RoundedCornerShape(4.dp))
                            else Modifier
                        )
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                )
                if (opt.lowData) {
                    Spacer(Modifier.width(4.dp))
                    Text("low data", style = MaterialTheme.typography.labelSmall, color = ReadinessOrange)
                }
            }
            MetricTokensRow(opt, metric, onMetricChange)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                headlineText(metric, opt),
                style = MaterialTheme.typography.titleMedium,
                color = headlineColor(metric, opt),
                fontWeight = FontWeight.Bold
            )
            Text(metric.label, style = MaterialTheme.typography.labelSmall, color = TextDisabled)
        }
    }
}

// ── Category card ──────────────────────────────────────────────────────────────

@Composable
private fun CategoryCard(
    category: SettingsCategoryResult,
    metric: OptionMetric,
    onMetricChange: (OptionMetric) -> Unit
) {
    val maxima = metricMaxima(category.options)
    val ranks = displayRanks(category.options, metric)
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceVariant)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                category.title,
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Ranked by ${metric.label} · tap a stat to re-rank",
                style = MaterialTheme.typography.labelSmall,
                color = TextDisabled
            )
            HorizontalDivider(color = SurfaceDark)
            val ordered = category.options.sortedByDescending { metric.value(it) }
            ordered.forEach { opt ->
                CategoryOptionRow(opt, maxima, metric, ranks.getValue(opt.key), onMetricChange)
            }
        }
    }
}

@Composable
private fun CategoryOptionRow(
    opt: SettingsOptionResult,
    maxima: Map<OptionMetric, Double>,
    metric: OptionMetric,
    displayRank: Int,
    onMetricChange: (OptionMetric) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RankBadge(displayRank)
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        opt.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (opt.lowData) {
                        Spacer(Modifier.width(6.dp))
                        Text("low data", style = MaterialTheme.typography.labelSmall, color = ReadinessOrange)
                    }
                }
                MetricTokensRow(opt, metric, onMetricChange)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    headlineText(metric, opt),
                    style = MaterialTheme.typography.titleMedium,
                    color = headlineColor(metric, opt),
                    fontWeight = FontWeight.Bold
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    when (metric) {
                        OptionMetric.AVG -> Text(
                            pctText(opt.pctVsGlobal) + " vs avg",
                            style = MaterialTheme.typography.labelSmall,
                            color = pctColor(opt.pctVsGlobal)
                        )
                        OptionMetric.SCORE -> Text(
                            pctText(opt.pctVsGlobal) + " raw",
                            style = MaterialTheme.typography.labelSmall,
                            color = pctColor(opt.pctVsGlobal)
                        )
                        else -> {}
                    }
                    DeltaText(opt.deltaPctVsPrev)
                }
            }
        }
        // Relative bar scaled to the active metric.
        val fraction = (metric.value(opt) / maxima.getValue(metric)).toFloat().coerceIn(0.05f, 1f)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(SurfaceDark)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        if (metric == OptionMetric.SCORE) scoreColor(opt.smartScore).copy(alpha = 0.7f)
                        else EcgCyan.copy(alpha = 0.6f)
                    )
            )
        }
    }
}

// ── Hour of day section ────────────────────────────────────────────────────────

@Composable
private fun HourOfDayCard(
    category: SettingsCategoryResult,
    metric: OptionMetric,
    onMetricChange: (OptionMetric) -> Unit
) {
    val maxima = metricMaxima(category.options)
    val ranks = displayRanks(category.options, metric)
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceVariant)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "Hour of Day",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                fontWeight = FontWeight.Bold
            )
            Text(
                "By start hour, ranked by ${metric.label} · tap a stat to re-rank",
                style = MaterialTheme.typography.labelSmall,
                color = TextDisabled
            )
            HorizontalDivider(color = SurfaceDark)
            HourAvgBarChart(category.options, metric)
            val ordered = category.options.sortedByDescending { metric.value(it) }
            ordered.forEach { opt ->
                CategoryOptionRow(opt, maxima, metric, ranks.getValue(opt.key), onMetricChange)
            }
        }
    }
}

/** At-a-glance bar chart: one bar per active hour (0–23), peak hour highlighted, keyed on the selected metric. */
@Composable
private fun HourAvgBarChart(options: List<SettingsOptionResult>, metric: OptionMetric) {
    if (options.isEmpty()) return
    val sorted = options.sortedBy { it.key.toIntOrNull() ?: 0 }
    val maxima = metricMaxima(sorted)
    val bestKey = sorted.maxByOrNull { metric.value(it) }?.key

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(modifier = Modifier.fillMaxWidth().height(110.dp)) {
            val slot = size.width / 24f
            val barW = slot * 0.6f
            sorted.forEach { opt ->
                val hour = opt.key.toIntOrNull() ?: return@forEach
                val fraction = (metric.value(opt) / maxima.getValue(metric)).toFloat().coerceIn(0.05f, 1f)
                val h = fraction * (size.height - 6f)
                val x = hour * slot + (slot - barW) / 2f
                drawRoundRect(
                    color = if (opt.key == bestKey) ReadinessGreen else EcgCyan.copy(alpha = 0.75f),
                    topLeft = Offset(x, size.height - h),
                    size = Size(barW, h),
                    cornerRadius = CornerRadius(3f, 3f)
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            listOf("00", "06", "12", "18", "23").forEach {
                Text(it, style = MaterialTheme.typography.labelSmall, color = TextDisabled)
            }
        }
        sorted.lastOrNull { it.key == bestKey }?.let { best ->
            Text(
                "Peak: ${best.displayName} · ${metricLabel(metric, best)}",
                style = MaterialTheme.typography.labelSmall,
                color = ReadinessGreen
            )
        }
    }
}

// ── Session type filter dialog ─────────────────────────────────────────────────

@Composable
private fun SessionTypeFilterDialog(
    selected: Set<String>,
    onApply: (Set<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var local by remember { mutableStateOf(selected) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
        title = { Text("Session Types", color = TextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "Choose which session types feed the comparison",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextDisabled
                )
                Spacer(Modifier.height(6.dp))
                ComparisonSessionType.entries.forEach { type ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .clickable {
                                local = if (type.key in local) local - type.key else local + type.key
                            }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = type.key in local,
                            onCheckedChange = {
                                local = if (it) local + type.key else local - type.key
                            },
                            colors = CheckboxDefaults.colors(checkedColor = EcgCyan)
                        )
                        Text(type.label, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "All",
                        style = MaterialTheme.typography.labelMedium,
                        color = EcgCyan,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(EcgCyan.copy(alpha = 0.15f))
                            .clickable { local = ComparisonSessionType.ALL_KEYS }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                    Text(
                        "None",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(SurfaceVariant)
                            .clickable { local = emptySet() }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (local.isNotEmpty()) onApply(local) },
                enabled = local.isNotEmpty()
            ) { Text("Apply", color = EcgCyan) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = TextSecondary) }
        }
    )
}

// ── Metric mode toggle ──────────────────────────────────────────────────────────

/** Segmented control switching every section below between raw averages and adjusted scores. */
@Composable
private fun MetricModeToggle(scoreMode: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(SurfaceDark)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        MetricModeOption("Avg", !scoreMode) { onChange(false) }
        MetricModeOption("Score", scoreMode) { onChange(true) }
    }
}

@Composable
private fun MetricModeOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = if (selected) TextPrimary else TextSecondary,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) EcgCyan.copy(alpha = 0.3f) else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 5.dp)
    )
}

/**
 * Sortable / displayable metrics for the comparison lists. Tapping a stat
 * token in any row swaps that metric into the headline and re-sorts the list.
 */
private enum class OptionMetric(val label: String) {
    AVG("avg"), BEST("best"), HOLDS("holds"), SCORE("score");

    fun value(opt: SettingsOptionResult): Double = when (this) {
        AVG -> opt.avgMs.toDouble()
        BEST -> opt.bestMs.toDouble()
        HOLDS -> opt.holdCount.toDouble()
        SCORE -> opt.smartScore
    }
}

/** Sub-text label for a metric token, e.g. "best 2:30" or "score 112". */
private fun metricLabel(metric: OptionMetric, opt: SettingsOptionResult): String = when (metric) {
    OptionMetric.AVG -> "avg ${formatHoldDuration(opt.avgMs)}"
    OptionMetric.BEST -> "best ${formatHoldDuration(opt.bestMs)}"
    OptionMetric.HOLDS -> "${opt.holdCount} holds"
    OptionMetric.SCORE -> "score ${opt.smartScore.toInt()}"
}

/** Max value per metric within a list, used to scale bars and the hour chart. */
private fun metricMaxima(options: List<SettingsOptionResult>): Map<OptionMetric, Double> = mapOf(
    OptionMetric.AVG to (options.maxOfOrNull { it.avgMs.toDouble() } ?: 1.0).coerceAtLeast(1.0),
    OptionMetric.BEST to (options.maxOfOrNull { it.bestMs.toDouble() } ?: 1.0).coerceAtLeast(1.0),
    OptionMetric.HOLDS to (options.maxOfOrNull { it.holdCount.toDouble() } ?: 1.0).coerceAtLeast(1.0),
    OptionMetric.SCORE to (options.maxOfOrNull { it.smartScore } ?: 1.0).coerceAtLeast(1.0)
)

private fun headlineText(metric: OptionMetric, opt: SettingsOptionResult): String = when (metric) {
    OptionMetric.AVG -> formatHoldDuration(opt.avgMs)
    OptionMetric.BEST -> formatHoldDuration(opt.bestMs)
    OptionMetric.HOLDS -> opt.holdCount.toString()
    OptionMetric.SCORE -> opt.smartScore.toInt().toString()
}

private fun headlineColor(metric: OptionMetric, opt: SettingsOptionResult) =
    if (metric == OptionMetric.SCORE) scoreColor(opt.smartScore) else TextPrimary

/** Clickable stat tokens for every metric except the active one; tapping swaps it in. */
@Composable
private fun MetricTokensRow(
    opt: SettingsOptionResult,
    metric: OptionMetric,
    onMetricChange: (OptionMetric) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OptionMetric.entries.filter { it != metric }.forEachIndexed { i, m ->
            if (i > 0) {
                Text("·", style = MaterialTheme.typography.labelSmall, color = TextDisabled)
            }
            Text(
                metricLabel(m, opt),
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { onMetricChange(m) }
                    .padding(horizontal = 3.dp, vertical = 1.dp)
            )
        }
    }
}

/** Rank per option key for display: options re-ranked by the active metric. */
private fun displayRanks(options: List<SettingsOptionResult>, metric: OptionMetric): Map<String, Int> {
    val byMetric = options.sortedByDescending { metric.value(it) }
    return options.associate { opt ->
        opt.key to (byMetric.indexOfFirst { it.key == opt.key } + 1)
    }
}

// ── Small shared pieces ────────────────────────────────────────────────────────

@Composable
private fun RankBadge(rank: Int) {
    val (label, color) = when (rank) {
        1 -> "🥇" to TextPrimary
        2 -> "🥈" to TextPrimary
        3 -> "🥉" to TextPrimary
        else -> "$rank" to TextSecondary
    }
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(SurfaceDark),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            style = if (rank <= 3) MaterialTheme.typography.bodyMedium
            else MaterialTheme.typography.labelMedium,
            color = color,
            fontWeight = FontWeight.Bold,
            fontSize = if (rank <= 3) 14.sp else 12.sp
        )
    }
}

@Composable
private fun DeltaText(deltaPct: Double?) {
    if (deltaPct == null) return
    Spacer(Modifier.width(6.dp))
    Text(
        text = "${if (deltaPct >= 0) "▲" else "▼"} ${"%.0f".format(deltaPct)}% prev",
        style = MaterialTheme.typography.labelSmall,
        color = pctColor(deltaPct)
    )
}

private fun pctColor(pct: Double) =
    if (pct >= 0.5) ReadinessGreen else if (pct <= -0.5) CoherencePink else TextDisabled

private fun pctText(pct: Double) = "%+.0f%%".format(pct)

private fun scoreColor(score: Double) = when {
    score >= 110 -> ReadinessGreen
    score >= 100 -> EcgCyan
    score >= 90 -> TextSecondary
    else -> CoherencePink
}

/** Formats a hold duration as m:ss (or h:mm if over an hour). */
internal fun formatHoldDuration(ms: Long): String {
    val totalSec = ms / 1000
    val minutes = totalSec / 60
    val seconds = totalSec % 60
    return if (minutes >= 60) {
        "%d:%02d:%02d".format(minutes / 60, minutes % 60, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

private val SHORT_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM")
