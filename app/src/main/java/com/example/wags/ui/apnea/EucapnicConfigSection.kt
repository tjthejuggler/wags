package com.example.wags.ui.apnea

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.wags.domain.model.EucapnicConfig
import com.example.wags.domain.usecase.breathing.EucapnicScalingEngine
import com.example.wags.ui.theme.*
import java.util.Locale
import kotlin.math.roundToInt

// ─────────────────────────────────────────────────────────────────────────────
// Eucapnic Diaphragmatic Breathing Configuration Section
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Configuration section for Eucapnic Diaphragmatic breathing preparation.
 *
 * Renders 7 sliders for all configurable parameters plus a read-only
 * inhale-to-exhale ratio display. Bi-directional scaling is handled by
 * the ViewModel via [EucapnicScalingEngine].
 *
 * @param config Current eucapnic configuration
 * @param scalingEngine Engine for calculating derived values (ratio display)
 * @param onPrepDurationChange Callback when prep duration slider changes
 * @param onBpmChange Callback when BPM slider changes (triggers proportional scaling)
 * @param onInhaleChange Callback when inhale duration changes (recalculates BPM)
 * @param onTopPauseChange Callback when top pause changes (recalculates BPM)
 * @param onExhaleChange Callback when exhale duration changes (recalculates BPM)
 * @param onBottomPauseChange Callback when bottom pause changes (recalculates BPM)
 * @param onBreathDepthChange Callback when breath depth changes
 * @param modifier Optional modifier
 */
@Composable
fun EucapnicConfigSection(
    config: EucapnicConfig,
    scalingEngine: EucapnicScalingEngine,
    onPrepDurationChange: (Int) -> Unit,
    onBpmChange: (Float) -> Unit,
    onInhaleChange: (Float) -> Unit,
    onTopPauseChange: (Float) -> Unit,
    onExhaleChange: (Float) -> Unit,
    onBottomPauseChange: (Float) -> Unit,
    onBreathDepthChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    showHeader: Boolean = true
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        // ── Section header ────────────────────────────────────────────────
        if (showHeader) {
            Text(
                text = "Eucapnic Diaphragmatic Breathing",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Slow, controlled diaphragmatic breathing to reduce CO₂ tolerance.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )

            Spacer(modifier = Modifier.height(8.dp))
        }

        // ── 1. Total Prep Duration ────────────────────────────────────────
        EucapnicSliderRow(
            label = "Total Prep Duration",
            description = "How long the breathing preparation phase lasts.",
            valueText = formatSeconds(config.prepDurationSec.toFloat()),
            value = config.prepDurationSec.toFloat(),
            valueRange = 60f..900f,
            steps = 27, // (900-60)/30 - 1 = 27 steps → 30s increments
            onValueChange = { onPrepDurationChange(it.roundToInt()) }
        )

        // ── 2. Breaths Per Minute ─────────────────────────────────────────
        EucapnicSliderRow(
            label = "Breaths Per Minute",
            description = "Target breathing rate. Changing this scales all timers proportionally.",
            valueText = formatBpm(config.breathsPerMin),
            value = config.breathsPerMin,
            valueRange = 3.0f..12.0f,
            steps = 89, // (12.0-3.0)/0.1 - 1 = 89 steps → 0.1 increments
            onValueChange = onBpmChange
        )

        // ── 3. Inhale Duration ────────────────────────────────────────────
        EucapnicSliderRow(
            label = "Inhale Duration",
            description = "Time to draw breath in through the nose.",
            valueText = formatSecondsDecimal(config.inhaleSec),
            value = config.inhaleSec,
            valueRange = 1.0f..10.0f,
            steps = 89, // (10.0-1.0)/0.1 - 1 = 89 steps
            onValueChange = onInhaleChange
        )

        // ── 4. Top Pause ──────────────────────────────────────────────────
        EucapnicSliderRow(
            label = "Top Pause",
            description = "Breath-hold pause at the top of the inhale.",
            valueText = formatSecondsDecimal(config.topPauseSec),
            value = config.topPauseSec,
            valueRange = 0.0f..5.0f,
            steps = 49, // (5.0-0.0)/0.1 - 1 = 49 steps
            onValueChange = onTopPauseChange
        )

        // ── 5. Exhale Duration ────────────────────────────────────────────
        EucapnicSliderRow(
            label = "Exhale Duration",
            description = "Time to release breath out slowly.",
            valueText = formatSecondsDecimal(config.exhaleSec),
            value = config.exhaleSec,
            valueRange = 1.0f..15.0f,
            steps = 139, // (15.0-1.0)/0.1 - 1 = 139 steps
            onValueChange = onExhaleChange
        )

        // ── 6. Bottom Pause ───────────────────────────────────────────────
        EucapnicSliderRow(
            label = "Bottom Pause",
            description = "Breath-hold pause at the bottom of the exhale.",
            valueText = formatSecondsDecimal(config.bottomPauseSec),
            value = config.bottomPauseSec,
            valueRange = 0.0f..5.0f,
            steps = 49, // (5.0-0.0)/0.1 - 1 = 49 steps
            onValueChange = onBottomPauseChange
        )

        // ── 7. Breath Depth Target ────────────────────────────────────────
        EucapnicSliderRow(
            label = "Breath Depth Target",
            description = "Percentage of vital capacity to use for each breath.",
            valueText = "${config.breathDepthPercent}%",
            value = config.breathDepthPercent.toFloat(),
            valueRange = 15f..50f,
            steps = 6, // (50-15)/5 - 1 = 6 steps → 5% increments
            onValueChange = { onBreathDepthChange(it.roundToInt()) }
        )

        // ── Read-only Inhale:Exhale ratio ─────────────────────────────────
        InhaleExhaleRatioRow(config = config, scalingEngine = scalingEngine)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Private sub-composables
// ─────────────────────────────────────────────────────────────────────────────

/**
 * A single labelled slider row with description and formatted value.
 */
@Composable
private fun EucapnicSliderRow(
    label: String,
    description: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = TextPrimary
            )
            Text(
                text = valueText,
                style = MaterialTheme.typography.bodySmall,
                color = TextPrimary,
                fontWeight = FontWeight.Medium
            )
        }
        Text(
            text = description,
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary
        )
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = TextPrimary,
                activeTrackColor = TextPrimary,
                inactiveTrackColor = SurfaceVariant,
                activeTickColor = TextPrimary,
                inactiveTickColor = SurfaceVariant
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * Read-only row showing the calculated inhale-to-exhale ratio.
 */
@Composable
private fun InhaleExhaleRatioRow(
    config: EucapnicConfig,
    scalingEngine: EucapnicScalingEngine
) {
    val (inhaleRatio, exhaleRatio) = scalingEngine.calculateInhaleExhaleRatio(config)
    val inhalePct = (inhaleRatio * 100).roundToInt()
    val exhalePct = (exhaleRatio * 100).roundToInt()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Inhale : Exhale Ratio",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )
        Text(
            text = "$inhalePct% : $exhalePct%",
            style = MaterialTheme.typography.bodySmall,
            color = TextPrimary,
            fontWeight = FontWeight.Medium
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Formatting helpers
// ─────────────────────────────────────────────────────────────────────────────

private fun formatSeconds(value: Float): String =
    String.format(Locale.US, "%.0fs", value)

private fun formatSecondsDecimal(value: Float): String =
    String.format(Locale.US, "%.1fs", value)

private fun formatBpm(value: Float): String =
    String.format(Locale.US, "%.1f BPM", value)
