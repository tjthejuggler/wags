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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.wags.domain.usecase.breathing.RfProtocol
import com.example.wags.ui.common.LiveSensorActionsCallback
import com.example.wags.ui.theme.SurfaceDark
import com.example.wags.ui.theme.SurfaceVariant
import com.example.wags.ui.theme.TextDisabled
import com.example.wags.ui.theme.TextPrimary

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
        subtitle    = "Optimal ±0.1 BPM × 3 min",
        description = "Fine-tunes your personal optimal rate with 0.1 BPM granularity. Requires prior history."
    ),
    ProtocolInfo(
        protocol    = RfProtocol.SLIDING_WINDOW,
        label       = "Sliding Window",
        duration    = "~16 min",
        subtitle    = "Chirp 6.75→4.5 BPM, continuous scan",
        description = "Continuous chirp scan. Finds resonance without stepping."
    ),
    ProtocolInfo(
        protocol    = RfProtocol.CUSTOM,
        label       = "Custom",
        duration    = "5–60 min",
        subtitle    = "Set your own duration",
        description = "Choose a total duration and the assessment auto-generates the right number of breathing rates and test lengths. Follows the same randomized order and offset as other protocols."
    )
)

// ---------------------------------------------------------------------------
// Screen
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssessmentPickerScreen(
    onNavigateBack: () -> Unit,
    onNavigateToSettings: () -> Unit = {},
    onStartAssessment: (RfProtocol, Boolean, Int) -> Unit,
    viewModel: AssessmentPickerViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Vibration toggle — persisted to SharedPreferences so it survives app restarts
    val prefs = remember { context.getSharedPreferences("apnea_prefs", android.content.Context.MODE_PRIVATE) }
    var vibrationEnabled by remember { mutableStateOf(prefs.getBoolean("breathing_vibration", false)) }

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
                actions = {
                    LiveSensorActionsCallback(onNavigateToSettings)
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

            // ── Custom duration slider ──────────────────────────────────────
            if (state.selectedProtocol == RfProtocol.CUSTOM) {
                CustomDurationSection(
                    durationMinutes = state.customDurationMinutes,
                    onDurationChange = { viewModel.setCustomDuration(it) }
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

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = {
                        onStartAssessment(
                            state.selectedProtocol,
                            vibrationEnabled,
                            state.customDurationMinutes
                        )
                    },
                    modifier = Modifier.weight(1f),
                    enabled = state.isHrDeviceConnected
                ) {
                    Text("Start Assessment")
                }

                IconButton(
                    onClick = {
                        vibrationEnabled = !vibrationEnabled
                        prefs.edit().putBoolean("breathing_vibration", vibrationEnabled).apply()
                    }
                ) {
                    Text(
                        text = "〰",
                        color = if (vibrationEnabled) TextPrimary else TextDisabled,
                        style = MaterialTheme.typography.titleLarge
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Custom duration section
// ---------------------------------------------------------------------------

@Composable
private fun CustomDurationSection(
    durationMinutes: Int,
    onDurationChange: (Int) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Duration",
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = "$durationMinutes min",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Slider(
                value = durationMinutes.toFloat(),
                onValueChange = { onDurationChange(it.toInt()) },
                valueRange = 6f..60f,
                steps = 26,  // 2-min increments: 6,8,10,...,60
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("6 min", style = MaterialTheme.typography.labelSmall, color = TextDisabled)
                Text("60 min", style = MaterialTheme.typography.labelSmall, color = TextDisabled)
            }

            // Show estimated session breakdown
            val breakdown = estimateCustomBreakdown(durationMinutes)
            Text(
                text = breakdown,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Estimates the session breakdown for a custom duration so the user
 * knows what to expect before starting. Matches the algorithm in
 * [RfAssessmentOrchestrator.customProtocolParams].
 */
private fun estimateCustomBreakdown(totalMinutes: Int): String {
    val baselineSec = 60L
    val washoutSec = 30L
    val availableSec = totalMinutes * 60L - baselineSec

    var bestN = 3
    var bestTestSec = 60L
    for (n in 3..7) {
        val testSec = (availableSec - (n - 1) * washoutSec) / n
        if (testSec < 60L) break
        bestN = n
        bestTestSec = testSec.coerceAtMost(300L)
    }

    val testMin = bestTestSec / 60
    val testSecRem = bestTestSec % 60
    val testLabel = if (testSecRem == 0L) "${testMin} min" else "${testMin}m ${testSecRem}s"

    return "$bestN rates × $testLabel each + 1 min baseline + 30 s rest"
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
