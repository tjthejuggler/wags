package com.example.wags.ui.apnea

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.example.wags.data.db.entity.ApneaRecordEntity
import com.example.wags.domain.model.ApneaTableType
import com.example.wags.ui.navigation.WagsRoutes
import com.example.wags.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApneaScreen(
    navController: NavController,
    viewModel: ApneaViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val deviceId = "PLACEHOLDER_H10_ID"

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = { Text("Apnea Training") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Text("←", style = MaterialTheme.typography.headlineMedium, color = EcgCyan)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Free Hold Section
            FreeHoldSection(
                freeHoldActive = state.freeHoldActive,
                freeHoldDurationMs = state.freeHoldDurationMs,
                onStart = { viewModel.startFreeHold(deviceId) },
                onStop = { viewModel.stopFreeHold() }
            )

            HorizontalDivider(color = SurfaceVariant)

            // Settings
            FreeHoldSettings(
                selectedLungVolume = state.selectedLungVolume,
                hyperventilationPrep = state.hyperventilationPrep,
                onLungVolumeChange = { viewModel.setLungVolume(it) },
                onHyperventilationChange = { viewModel.setHyperventilationPrep(it) }
            )

            HorizontalDivider(color = SurfaceVariant)

            // Personal Best & Table Navigation
            PersonalBestSection(
                personalBestMs = state.personalBestMs,
                onSetPersonalBest = { viewModel.setPersonalBest(it) },
                onNavigateO2 = { navController.navigate(WagsRoutes.apneaTable("O2")) },
                onNavigateCo2 = { navController.navigate(WagsRoutes.apneaTable("CO2")) }
            )

            HorizontalDivider(color = SurfaceVariant)

            // Recent Records
            if (state.recentRecords.isNotEmpty()) {
                RecentRecordsSection(records = state.recentRecords)
            }
        }
    }
}

@Composable
private fun FreeHoldSection(
    freeHoldActive: Boolean,
    freeHoldDurationMs: Long,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceVariant)) {
        Column(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Free Hold", style = MaterialTheme.typography.titleLarge)

            if (freeHoldActive) {
                Text(
                    "HOLD",
                    style = MaterialTheme.typography.displayLarge,
                    color = ApneaHold
                )
                Button(
                    onClick = onStop,
                    colors = ButtonDefaults.buttonColors(containerColor = ApneaHold),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Release") }
            } else {
                if (freeHoldDurationMs > 0L) {
                    val seconds = freeHoldDurationMs / 1000L
                    val millis = (freeHoldDurationMs % 1000L) / 10L
                    Text(
                        "Last: ${seconds}s ${millis}ms",
                        style = MaterialTheme.typography.headlineMedium,
                        color = EcgCyan
                    )
                }
                Button(
                    onClick = onStart,
                    colors = ButtonDefaults.buttonColors(containerColor = ApneaVentilation),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Start Hold") }
            }
        }
    }
}

@Composable
private fun FreeHoldSettings(
    selectedLungVolume: String,
    hyperventilationPrep: Boolean,
    onLungVolumeChange: (String) -> Unit,
    onHyperventilationChange: (Boolean) -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Settings", style = MaterialTheme.typography.titleLarge)

            Text("Lung Volume", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("FULL", "PARTIAL", "EMPTY").forEach { volume ->
                    FilterChip(
                        selected = selectedLungVolume == volume,
                        onClick = { onLungVolumeChange(volume) },
                        label = { Text(volume) }
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Hyperventilation Prep", style = MaterialTheme.typography.bodyLarge)
                Switch(
                    checked = hyperventilationPrep,
                    onCheckedChange = onHyperventilationChange
                )
            }
        }
    }
}

@Composable
private fun PersonalBestSection(
    personalBestMs: Long,
    onSetPersonalBest: (Long) -> Unit,
    onNavigateO2: () -> Unit,
    onNavigateCo2: () -> Unit
) {
    var pbInput by remember { mutableStateOf("") }

    Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Table Training", style = MaterialTheme.typography.titleLarge)

            if (personalBestMs > 0L) {
                Text(
                    "Personal Best: ${personalBestMs / 1000L}s",
                    style = MaterialTheme.typography.bodyLarge,
                    color = EcgCyan
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = pbInput,
                    onValueChange = { pbInput = it },
                    label = { Text("PB (seconds)") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                Button(onClick = {
                    pbInput.toLongOrNull()?.let { onSetPersonalBest(it * 1000L) }
                }) { Text("Set") }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onNavigateO2,
                    enabled = personalBestMs > 0L,
                    modifier = Modifier.weight(1f)
                ) { Text("O2 Table") }
                Button(
                    onClick = onNavigateCo2,
                    enabled = personalBestMs > 0L,
                    modifier = Modifier.weight(1f)
                ) { Text("CO2 Table") }
            }
        }
    }
}

@Composable
private fun RecentRecordsSection(records: List<ApneaRecordEntity>) {
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Recent Records", style = MaterialTheme.typography.titleLarge)
            records.take(5).forEach { record ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "${record.durationMs / 1000L}s — ${record.lungVolume}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        record.tableType ?: "Free",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary
                    )
                }
            }
        }
    }
}
