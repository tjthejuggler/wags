package com.example.wags.ui.breathing

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.wags.domain.usecase.breathing.RfProtocol
import com.example.wags.ui.theme.SurfaceDark
import com.example.wags.ui.theme.SurfaceVariant

// ---------------------------------------------------------------------------
// Protocol metadata
// ---------------------------------------------------------------------------

private data class ProtocolInfo(
    val protocol: RfProtocol,
    val label: String,
    val duration: String,
    val subtitle: String,
    val description: String
)

private val PROTOCOL_LIST = listOf(
    ProtocolInfo(
        protocol    = RfProtocol.EXPRESS,
        label       = "Express",
        duration    = "~8 min",
        subtitle    = "5 rates × 1 min, quick scan",
        description = "Quick 5-rate sweep. Best for daily check-ins."
    ),
    ProtocolInfo(
        protocol    = RfProtocol.STANDARD,
        label       = "Standard",
        duration    = "~18 min",
        subtitle    = "5 rates × 2 min, standard calibration",
        description = "Standard 5-rate calibration. Recommended for weekly use."
    ),
    ProtocolInfo(
        protocol    = RfProtocol.DEEP,
        label       = "Deep",
        duration    = "~42 min",
        subtitle    = "13 combos × 3 min, deep calibration",
        description = "Full 13-combo deep calibration. Use monthly."
    ),
    ProtocolInfo(
        protocol    = RfProtocol.TARGETED,
        label       = "Targeted",
        duration    = "~10 min",
        subtitle    = "Optimal ±0.2 BPM × 3 min",
        description = "Fine-tunes your personal optimal rate. Requires prior history."
    ),
    ProtocolInfo(
        protocol    = RfProtocol.SLIDING_WINDOW,
        label       = "Sliding Window",
        duration    = "~16 min",
        subtitle    = "Chirp 6.75→4.5 BPM, continuous scan",
        description = "Continuous chirp scan. Finds resonance without stepping."
    )
)

// ---------------------------------------------------------------------------
// Screen
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssessmentPickerScreen(
    onNavigateBack: () -> Unit,
    onStartAssessment: (RfProtocol) -> Unit,
    viewModel: AssessmentPickerViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = com.example.wags.ui.theme.BackgroundDark,
        topBar = {
            TopAppBar(
                title = { Text("RF Assessment") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark)
            )
        }
    ) { padding ->
        if (state.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Select Protocol",
                style = MaterialTheme.typography.titleMedium
            )

            PROTOCOL_LIST.forEach { info ->
                val isTargeted = info.protocol == RfProtocol.TARGETED
                val isDisabled = isTargeted && !state.targetedEnabled
                val isSelected = state.selectedProtocol == info.protocol

                ProtocolCard(
                    info       = info,
                    isSelected = isSelected,
                    isDisabled = isDisabled,
                    onClick    = { if (!isDisabled) viewModel.selectProtocol(info.protocol) }
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Description card for selected protocol
            val selectedInfo = PROTOCOL_LIST.first { it.protocol == state.selectedProtocol }
            DescriptionCard(info = selectedInfo)

            Spacer(modifier = Modifier.height(8.dp))

            // ── HR device gate ────────────────────────────────────────────────
            if (!state.isHrDeviceConnected) {
                HrRequiredBanner()
            }

            Button(
                onClick = { onStartAssessment(state.selectedProtocol) },
                modifier = Modifier.fillMaxWidth(),
                enabled = state.isHrDeviceConnected
            ) {
                Text("Start Assessment")
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Protocol card
// ---------------------------------------------------------------------------

@Composable
private fun ProtocolCard(
    info: ProtocolInfo,
    isSelected: Boolean,
    isDisabled: Boolean,
    onClick: () -> Unit
) {
    val containerColor = when {
        isSelected -> MaterialTheme.colorScheme.primaryContainer
        else       -> SurfaceVariant
    }
    val contentAlpha = if (isDisabled) 0.4f else 1f

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(contentAlpha)
            .then(
                if (!isDisabled) Modifier.clickable(onClick = onClick)
                else Modifier
            )
            .then(
                if (isSelected) Modifier.border(
                    width = 1.5.dp,
                    color = MaterialTheme.colorScheme.primary,
                    shape = MaterialTheme.shapes.medium
                ) else Modifier
            ),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text  = info.label,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    if (isDisabled) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector        = Icons.Default.Lock,
                            contentDescription = "Requires prior session history",
                            modifier           = Modifier.size(14.dp)
                        )
                    }
                }
                Text(
                    text  = info.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (isDisabled) {
                    Text(
                        text  = "Requires prior session history",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            Text(
                text  = info.duration,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ---------------------------------------------------------------------------
// HR required banner
// ---------------------------------------------------------------------------

@Composable
private fun HrRequiredBanner() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)
        ),
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp)
            )
            Column {
                Text(
                    text = "HR Device Required",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error
                )
                Text(
                    text = "Connect a Polar H10, Verity Sense, or pulse oximeter to run an RF Assessment.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Description card
// ---------------------------------------------------------------------------

@Composable
private fun DescriptionCard(info: ProtocolInfo) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(containerColor = SurfaceDark)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text  = info.label,
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                text  = info.duration,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            Text(
                text  = info.description,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}
