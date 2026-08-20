package com.example.wags.ui.apnea

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.wags.domain.model.PersonalBestEntry
import com.example.wags.domain.model.TimeBuckets
import com.example.wags.domain.model.TimeOfDay
import com.example.wags.ui.theme.SurfaceVariant
import com.example.wags.ui.theme.TextPrimary
import com.example.wags.ui.theme.TextSecondary

/**
 * Time-scope selection for the trophy / personal-bests screens.
 * Purely a display filter — it never changes any values, only trims rows.
 */
enum class TrophyTimeFilterMode { ALL, TIME_OF_DAY, HOUR }

/**
 * Whether a personal-best entry is visible under the given time filter.
 *
 *  * [TrophyTimeFilterMode.ALL] — no time filtering.
 *  * [TrophyTimeFilterMode.TIME_OF_DAY] — only entries whose time bucket falls
 *    inside the chosen Morning/Day/Night window (hour buckets are mapped back
 *    through [TimeBuckets.timeOfDayNameOf]; legacy names match directly).
 *  * [TrophyTimeFilterMode.HOUR] — only entries pinned to the chosen hour.
 *
 * Entries that don't involve the time setting at all (Global, single non-tod
 * settings, non-tod combos) always pass. `showEmpty` additionally gates rows
 * that hold no record yet.
 */
fun PersonalBestEntry.matchesTrophyTimeFilter(
    mode: TrophyTimeFilterMode,
    filterTod: TimeOfDay?,
    filterHour: Int?,
    showEmpty: Boolean
): Boolean {
    if (!showEmpty && durationMs == null) return false
    if (mode == TrophyTimeFilterMode.ALL) return true
    // The entry's own timeOfDay ("" = time setting not involved in this combo).
    val entryTod = timeOfDay
    if (entryTod.isEmpty()) return true
    return when (mode) {
        TrophyTimeFilterMode.TIME_OF_DAY ->
            filterTod != null && TimeBuckets.timeOfDayNameOf(entryTod) == filterTod.name
        TrophyTimeFilterMode.HOUR ->
            filterHour != null && entryTod == TimeBuckets.fromHour(filterHour)
        TrophyTimeFilterMode.ALL -> true
    }
}

@Composable
private fun FilterPill(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = if (selected) TextPrimary else TextSecondary,
        modifier = Modifier
            .background(
                if (selected) SurfaceVariant else Color.Transparent,
                RoundedCornerShape(8.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

/**
 * Radio-style time filter shown at the top of the trophy / personal-bests
 * screens: `All | Time of Day ▾ | Hour ▾` plus a "Show Empty" toggle label.
 * The two dropdown pills act as radio buttons — picking an option from a
 * dropdown selects that mode.
 */
@Composable
fun TrophyTimeFilterBar(
    mode: TrophyTimeFilterMode,
    timeOfDay: TimeOfDay?,
    hour: Int?,
    showEmpty: Boolean,
    onModeChange: (TrophyTimeFilterMode) -> Unit,
    onTimeOfDayChange: (TimeOfDay?) -> Unit,
    onHourChange: (Int?) -> Unit,
    onShowEmptyChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var todMenuOpen by remember { mutableStateOf(false) }
    var hourMenuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        FilterPill(
            label = "All",
            selected = mode == TrophyTimeFilterMode.ALL,
            onClick = { onModeChange(TrophyTimeFilterMode.ALL) }
        )

        Column {
            FilterPill(
                label = if (timeOfDay != null) "Tod: ${timeOfDay.displayName()}" else "Time of Day",
                selected = mode == TrophyTimeFilterMode.TIME_OF_DAY,
                onClick = { todMenuOpen = true }
            )
            DropdownMenu(expanded = todMenuOpen, onDismissRequest = { todMenuOpen = false }) {
                TimeOfDay.entries.forEach { tod ->
                    DropdownMenuItem(
                        text = { Text(tod.displayName()) },
                        onClick = {
                            onTimeOfDayChange(tod)
                            onModeChange(TrophyTimeFilterMode.TIME_OF_DAY)
                            todMenuOpen = false
                        }
                    )
                }
            }
        }

        Column {
            FilterPill(
                label = if (hour != null) "Hour: ${TimeBuckets.display(TimeBuckets.fromHour(hour))}" else "Hour",
                selected = mode == TrophyTimeFilterMode.HOUR,
                onClick = { hourMenuOpen = true }
            )
            DropdownMenu(expanded = hourMenuOpen, onDismissRequest = { hourMenuOpen = false }) {
                TimeBuckets.HOUR_BUCKETS.forEach { bucket ->
                    DropdownMenuItem(
                        text = { Text(TimeBuckets.display(bucket)) },
                        onClick = {
                            onHourChange(TimeBuckets.hourOf(bucket))
                            onModeChange(TrophyTimeFilterMode.HOUR)
                            hourMenuOpen = false
                        }
                    )
                }
            }
        }

        FilterPill(
            label = "Show Empty",
            selected = showEmpty,
            onClick = { onShowEmptyChange(!showEmpty) }
        )
    }
}
