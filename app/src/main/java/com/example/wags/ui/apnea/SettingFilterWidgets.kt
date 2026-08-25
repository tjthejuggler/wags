package com.example.wags.ui.apnea

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.wags.domain.model.AudioSetting
import com.example.wags.domain.model.Posture
import com.example.wags.domain.model.PrepType
import com.example.wags.domain.model.TimeBuckets
import com.example.wags.domain.model.TimeOfDay
import com.example.wags.ui.theme.ButtonPrimary
import com.example.wags.ui.theme.SurfaceDark
import com.example.wags.ui.theme.SurfaceVariant
import com.example.wags.ui.theme.TextPrimary
import com.example.wags.ui.theme.TextSecondary

/**
 * Option spaces for the 5 standard apnea setting filters shared by the
 * session-history filter UIs (All Records, Progressive O₂, Min Breath,
 * Contraction Table).
 *
 * Filter state is a [Set] of the selected option values:
 *  * every option selected → category is unfiltered;
 *  * empty set → nothing matches (the "None" state of the header toggle).
 */
object SettingFilterOptions {
    val LUNG_VOLUMES: List<String> = listOf("FULL", "PARTIAL", "EMPTY")
    val PREP_TYPES: List<String> = PrepType.entries.map { it.name }
    val POSTURES: List<String> = Posture.entries.map { it.name }
    val AUDIOS: List<String> = AudioSetting.entries.map { it.name }

    /** Time-of-day option space: the 24 hour buckets in BY_HOUR mode, Morning/Day/Night otherwise. */
    fun timeOfDayOptions(byHour: Boolean): List<String> =
        if (byHour) TimeBuckets.HOUR_BUCKETS else TimeOfDay.entries.map { it.name }

    fun lungVolumeLabel(value: String): String =
        if (value == "PARTIAL") "Half" else value.lowercase().replaceFirstChar { it.uppercase() }

    fun prepTypeLabel(value: String): String =
        runCatching { PrepType.valueOf(value).displayName() }.getOrDefault(value)

    /** Compact chip label ("Eucapnic" instead of "Eucapnic Diaphragmatic"). */
    fun prepTypeShortLabel(value: String): String =
        runCatching { PrepType.valueOf(value).shortDisplayName() }.getOrDefault(value)

    fun postureLabel(value: String): String =
        runCatching { Posture.valueOf(value).displayName() }.getOrDefault(value)

    fun audioLabel(value: String): String =
        runCatching { AudioSetting.valueOf(value).displayName() }.getOrDefault(value)

    fun timeBucketLabel(value: String): String = TimeBuckets.display(value)
}

/** True when [this] selection includes every value of [options] (category unfiltered). */
fun Set<String>.coversAll(options: Collection<String>): Boolean = containsAll(options)

/**
 * One summary token for a filter category, or null when the category is
 * unfiltered (every option selected) and should be omitted from the summary.
 *  * nothing selected → "None"
 *  * up to 3 selected → their labels joined with "/"
 *  * more selected    → "n of total"
 */
fun settingFilterSummaryPart(
    selected: Set<String>,
    options: Collection<String>,
    labelOf: (String) -> String
): String? = when {
    selected.isEmpty() -> "None"
    selected.coversAll(options) -> null
    selected.size <= 3 -> selected.joinToString("/") { labelOf(it) }
    else -> "${selected.size} of ${options.size}"
}

/**
 * Small, discrete All/None toggle shown next to a filter category header —
 * deliberately styled unlike the option chips.
 *
 * Shows "None" while more than half of the options are selected (tapping
 * clears the selection) and "All" while half or fewer are selected (tapping
 * selects every option).
 */
@Composable
fun AllNoneHeaderToggle(
    selectedCount: Int,
    totalCount: Int,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val noneMode = selectedCount * 2 > totalCount
    Surface(
        onClick = onToggle,
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = SurfaceDark,
        contentColor = if (noneMode) ButtonPrimary else TextSecondary,
        border = BorderStroke(
            width = 1.dp,
            color = if (noneMode) ButtonPrimary else TextSecondary.copy(alpha = 0.4f)
        )
    ) {
        Text(
            text = if (noneMode) "None" else "All",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 1.dp)
        )
    }
}

/**
 * One filter category: a header row (label + All/None toggle) above a flow of
 * multi-select option chips. Tapping a chip toggles that single option.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MultiSelectFilterCategory(
    label: String,
    options: List<String>,
    optionLabel: (String) -> String,
    selected: Set<String>,
    onSelectionChange: (Set<String>) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = TextSecondary)
            AllNoneHeaderToggle(
                selectedCount = selected.size,
                totalCount = options.size,
                onToggle = {
                    if (selected.size * 2 > options.size) onSelectionChange(emptySet())
                    else onSelectionChange(options.toSet())
                }
            )
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            options.forEach { value ->
                FilterChip(
                    selected = value in selected,
                    onClick = {
                        onSelectionChange(
                            if (value in selected) selected - value else selected + value
                        )
                    },
                    label = { Text(optionLabel(value), style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.height(30.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = SurfaceVariant,
                        selectedLabelColor = TextPrimary,
                        labelColor = TextSecondary
                    )
                )
            }
        }
    }
}
