package com.example.wags.ui.morning

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight

@Composable
fun HelpBubble(
    title: String,
    text: String,
    modifier: Modifier = Modifier
) {
    var showDialog by remember { mutableStateOf(false) }

    IconButton(onClick = { showDialog = true }, modifier = modifier) {
        Icon(
            imageVector = Icons.Outlined.Info,
            contentDescription = "Info: $title",
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(title, style = MaterialTheme.typography.titleMedium) },
            text = { Text(text, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = { showDialog = false }) { Text("Got it") }
            }
        )
    }
}

@Composable
fun MetricRowWithHelp(
    label: String,
    value: String,
    helpTitle: String,
    helpText: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold
        )
        HelpBubble(title = helpTitle, text = helpText)
    }
}
