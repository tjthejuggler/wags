package com.example.wags.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.wags.data.ipc.HabitIntegrationRepository.Slot
import com.example.wags.domain.model.HabitEntry
import com.example.wags.ui.theme.EcgCyan
import com.example.wags.ui.theme.ReadinessGreen
import com.example.wags.ui.theme.ReadinessOrange
import com.example.wags.ui.theme.SurfaceDark
import com.example.wags.ui.theme.SurfaceVariant
import com.example.wags.ui.theme.TextPrimary
import com.example.wags.ui.theme.TextSecondary

// ── Tail App integration sub-section ──────────────────────────────────────────

@Composable
fun TailAppIntegrationSection(
    habitList: List<HabitEntry>,
    isLoading: Boolean,
    habitAppUnavailable: Boolean,
    freeHoldHabit: HabitSlotSelection,
    apneaNewRecordHabit: HabitSlotSelection,
    o2TableHabit: HabitSlotSelection,
    co2TableHabit: HabitSlotSelection,
    morningReadinessHabit: HabitSlotSelection,
    hrvReadinessHabit: HabitSlotSelection,
    resonanceBreathingHabit: HabitSlotSelection,
    meditationHabit: HabitSlotSelection,
    rapidHrChangeHabit: HabitSlotSelection,
    progressiveO2Habit: HabitSlotSelection,
    minBreathHabit: HabitSlotSelection,
    tillContractionHabit: HabitSlotSelection,
    contractionCountHabit: HabitSlotSelection,
    musicHabit: HabitSlotSelection,
    onSelectHabit: (Slot, HabitEntry) -> Unit,
    onClearHabit: (Slot) -> Unit,
    onRefresh: () -> Unit,
    isBackfilling: Boolean = false,
    backfillMessage: String? = null,
    backfillError: String? = null,
    onBackfill: () -> Unit = {},
    onDismissBackfillMsg: () -> Unit = {}
) {
    var pickerSlot by remember { mutableStateOf<Slot?>(null) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SettingsSubSectionLabel("Tail App")
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = EcgCyan,
                    strokeWidth = 2.dp
                )
            } else {
                TextButton(onClick = onRefresh) {
                    Text("Refresh", style = MaterialTheme.typography.bodySmall, color = EcgCyan)
                }
            }
        }
        Text(
            "Choose which habit to increment for each activity.",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )

        when {
            habitAppUnavailable ->
                Text(
                    "Tail app not found. Make sure it is installed and tap Refresh.",
                    style = MaterialTheme.typography.bodySmall,
                    color = ReadinessOrange
                )
            !isLoading && habitList.isEmpty() ->
                Text(
                    "No habits found. Tap Refresh to load from the Tail app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
        }

        val slots = listOf(
            Slot.FREE_HOLD           to freeHoldHabit,
            Slot.APNEA_NEW_RECORD    to apneaNewRecordHabit,
            Slot.O2_TABLE            to o2TableHabit,
            Slot.CO2_TABLE           to co2TableHabit,
            Slot.PROGRESSIVE_O2      to progressiveO2Habit,
            Slot.MIN_BREATH          to minBreathHabit,
            Slot.TILL_CONTRACTION    to tillContractionHabit,
            Slot.CONTRACTION_COUNT   to contractionCountHabit,
            Slot.MORNING_READINESS   to morningReadinessHabit,
            Slot.HRV_READINESS       to hrvReadinessHabit,
            Slot.RESONANCE_BREATHING to resonanceBreathingHabit,
            Slot.MEDITATION          to meditationHabit,
            Slot.RAPID_HR_CHANGE     to rapidHrChangeHabit,
            Slot.MUSIC               to musicHabit
        )

        slots.forEachIndexed { index, (slot, selection) ->
            if (index > 0) {
                SettingsSubSectionDivider()
            }
            HabitSlotRow(
                slot      = slot,
                selection = selection,
                onClick   = { pickerSlot = slot },
                onClear   = { onClearHabit(slot) }
            )
        }

        // Searchable habit-picker popup for the slot being configured
        pickerSlot?.let { slot ->
            HabitPickerDialog(
                slot        = slot,
                habitList   = habitList,
                selectedId  = slots.firstOrNull { it.first == slot }?.second?.habitId ?: "",
                onSelect    = { entry ->
                    onSelectHabit(slot, entry)
                    pickerSlot = null
                },
                onDismiss   = { pickerSlot = null }
            )
        }

        // ── Retroactive backfill ───────────────────────────────────────────
        SettingsSubSectionDivider()

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Backfill Past Sessions",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "Send minutes and session counts from all past sessions " +
                            "to Tail. Connecting a new habit backfills its " +
                            "history automatically.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
            if (isBackfilling) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = EcgCyan,
                    strokeWidth = 2.dp
                )
            } else {
                TextButton(onClick = onBackfill) {
                    Text("Send", style = MaterialTheme.typography.bodySmall, color = EcgCyan)
                }
            }
        }

        // Show result or error message
        backfillMessage?.let { msg ->
            Text(
                msg,
                style = MaterialTheme.typography.bodySmall,
                color = ReadinessGreen,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onDismissBackfillMsg() }
            )
        }
        backfillError?.let { err ->
            Text(
                err,
                style = MaterialTheme.typography.bodySmall,
                color = ReadinessOrange,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onDismissBackfillMsg() }
            )
        }
    }
}

@Composable
private fun HabitSlotRow(
    slot: Slot,
    selection: HabitSlotSelection,
    onClick: () -> Unit,
    onClear: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(slot.label, style = MaterialTheme.typography.bodyMedium)
            if (selection.isSet) {
                Text(
                    selection.displayName,
                    style = MaterialTheme.typography.bodySmall,
                    color = ReadinessGreen
                )
            } else {
                Text(
                    "Not set",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selection.isSet) {
                IconButton(onClick = onClear, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.Clear,
                        contentDescription = "Clear",
                        tint = TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            OutlinedButton(
                onClick = onClick,
                modifier = Modifier.height(32.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
            ) {
                Text(
                    if (selection.isSet) "Change" else "Set",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

/**
 * Searchable, alphabetically sorted habit picker popup for a Tail slot.
 * Opens when the slot's "Set"/"Change" button is tapped.
 */
@Composable
private fun HabitPickerDialog(
    slot: Slot,
    habitList: List<HabitEntry>,
    selectedId: String,
    onSelect: (HabitEntry) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }

    val filtered = remember(habitList, query) {
        val sorted = habitList.sortedBy { it.habitName.lowercase() }
        if (query.isBlank()) sorted
        else sorted.filter { it.habitName.contains(query.trim(), ignoreCase = true) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
        title = {
            Column {
                Text("Select Habit", style = MaterialTheme.typography.titleMedium)
                Text(
                    slot.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search habits") },
                    leadingIcon = {
                        Icon(Icons.Default.Search, contentDescription = null, tint = TextSecondary)
                    },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(
                                    Icons.Default.Clear,
                                    contentDescription = "Clear search",
                                    tint = TextSecondary
                                )
                            }
                        }
                    },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small
                )
                Spacer(Modifier.height(8.dp))
                when {
                    habitList.isEmpty() ->
                        Text(
                            "No habits available. Tap Refresh on the Tail section.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    filtered.isEmpty() ->
                        Text(
                            "No habits match \"${query.trim()}\".",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    else ->
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 360.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            // No explicit key: Tail can return duplicate/blank habit
                            // ids (id == habit name), which would crash LazyColumn.
                            items(filtered) { entry ->
                                HabitPickerRow(
                                    entry      = entry,
                                    isSelected = entry.habitId == selectedId,
                                    onClick    = { onSelect(entry) }
                                )
                            }
                        }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun HabitPickerRow(
    entry: HabitEntry,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = if (isSelected) SurfaceVariant else Color.Transparent,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = entry.habitName,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isSelected) TextPrimary else TextSecondary,
                modifier = Modifier.weight(1f)
            )
            if (isSelected) {
                Text("✓", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
            }
        }
    }
}
