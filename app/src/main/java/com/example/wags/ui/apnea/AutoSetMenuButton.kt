package com.example.wags.ui.apnea

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Small "auto set" text button shown in a drill card header, immediately left
 * of the navigation arrow. Opens a menu with the available auto-set strategies:
 *
 * - "easiest": the settings combination with the highest predicted
 *   record-breaking probability (only offered where a forecast exists —
 *   Free Hold, Progressive O₂, Min Breath);
 * - "record": the settings of the personal-best hold for the current time
 *   bucket. For drills with an internal configuration (breath period /
 *   session duration) the current configuration is kept when a record exists
 *   with it, otherwise the configuration is switched to the best record's.
 *   When the top hold's prep type is locked, it is previewed for 2 seconds
 *   before falling back to the best unlocked-prep hold.
 */
@Composable
fun AutoSetMenuButton(
    onRecord: () -> Unit,
    onEasiest: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        Text(
            "auto set",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clickable { menuOpen = true }
                .padding(horizontal = 8.dp, vertical = 2.dp)
        )
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            onEasiest?.let { easiest ->
                DropdownMenuItem(
                    text = { Text("easiest") },
                    onClick = {
                        menuOpen = false
                        easiest()
                    }
                )
            }
            DropdownMenuItem(
                text = { Text("record") },
                onClick = {
                    menuOpen = false
                    onRecord()
                }
            )
        }
    }
}
