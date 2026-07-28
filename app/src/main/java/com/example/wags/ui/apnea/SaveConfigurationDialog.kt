package com.example.wags.ui.apnea

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.wags.ui.theme.*

/**
 * Simple dialog prompting the user to name and save the current
 * eucapnic breathing configuration.
 *
 * @param onSave Callback with the entered name when Save is tapped
 * @param onDismiss Callback when the dialog is dismissed or Cancel is tapped
 */
@Composable
fun SaveConfigurationDialog(
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = SurfaceDark,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // ── Title ───────────────────────────────────────────────────
                Text(
                    text = "Save Configuration",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )

                // ── Name input ──────────────────────────────────────────────
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Configuration name", color = TextSecondary) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = TextPrimary,
                        unfocusedBorderColor = SurfaceVariant,
                        cursorColor = TextPrimary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                // ── Buttons ─────────────────────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", color = TextSecondary)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { onSave(name) },
                        enabled = name.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SurfaceVariant,
                            contentColor = TextPrimary,
                            disabledContainerColor = SurfaceVariant.copy(alpha = 0.4f),
                            disabledContentColor = TextDisabled
                        )
                    ) {
                        Text("Save")
                    }
                }
            }
        }
    }
}
