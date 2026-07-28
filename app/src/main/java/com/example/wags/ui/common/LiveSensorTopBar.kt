package com.example.wags.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.example.wags.data.ble.HrDataSource
import com.example.wags.ui.navigation.WagsRoutes
import com.example.wags.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

// ── ViewModel ────────────────────────────────────────────────────────────────

/**
 * Lightweight ViewModel that provides live HR and SpO₂ from [HrDataSource].
 * Use this in screens that don't already have liveHr/liveSpO2 in their own
 * state but still need to show the sensor top-bar.
 */
@HiltViewModel
class LiveSensorViewModel @Inject constructor(
    hrDataSource: HrDataSource
) : androidx.lifecycle.ViewModel() {
    val liveHr = hrDataSource.liveHr
    val liveSpO2 = hrDataSource.liveSpO2
}

// ── Composables ──────────────────────────────────────────────────────────────

/**
 * Reusable top-app-bar actions block that shows live HR and SpO₂ readings
 * whenever they are available. Drop this into the `actions` slot of any
 * [TopAppBar] to get the same live-sensor display as the Dashboard.
 *
 * @param onClick Optional click handler — when provided, the entire row
 *   becomes clickable (e.g. to navigate to device settings).
 */
@Composable
fun LiveSensorActions(liveHr: Int?, liveSpO2: Int?, onClick: (() -> Unit)? = null) {
    if (liveHr != null || liveSpO2 != null) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .padding(end = 8.dp)
                .then(
                    if (onClick != null) Modifier.clickable(onClick = onClick)
                    else Modifier
                )
        ) {
            liveHr?.let { bpm ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Favorite,
                        contentDescription = "Heart rate",
                        tint = TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "$bpm",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                }
            }
            liveSpO2?.let { spo2 ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = "SpO₂",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                    Text(
                        text = "$spo2%",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                }
            }
        }
    }
}

/**
 * Self-contained version that pulls live sensor data from [LiveSensorViewModel]
 * and navigates to the Settings screen on click.
 *
 * Use this in screens that do NOT already have liveHr/liveSpO2 in their own
 * UI state — just drop it into the `actions` slot of a [TopAppBar]:
 *
 * ```
 * TopAppBar(
 *     …,
 *     actions = { LiveSensorActionsNav(navController) }
 * )
 * ```
 *
 * @return Boolean indicating whether sensor data is currently visible
 */
@Composable
fun LiveSensorActionsNav(
    navController: NavController,
    viewModel: LiveSensorViewModel = hiltViewModel()
): Boolean {
    val liveHr by viewModel.liveHr.collectAsStateWithLifecycle()
    val liveSpO2 by viewModel.liveSpO2.collectAsStateWithLifecycle()
    val hasSensorData = liveHr != null || liveSpO2 != null
    
    if (hasSensorData) {
        LiveSensorActions(
            liveHr = liveHr,
            liveSpO2 = liveSpO2,
            onClick = { navController.navigate(WagsRoutes.SETTINGS) }
        )
    }
    
    return hasSensorData
}

/**
 * Callback-based version for screens that use `onNavigateBack`-style
 * navigation instead of holding a [NavController] directly.
 *
 * ```
 * TopAppBar(
 *     …,
 *     actions = { LiveSensorActionsCallback(onNavigateToSettings) }
 * )
 * ```
 */
@Composable
fun LiveSensorActionsCallback(
    onNavigateToSettings: () -> Unit,
    viewModel: LiveSensorViewModel = hiltViewModel()
) {
    val liveHr by viewModel.liveHr.collectAsStateWithLifecycle()
    val liveSpO2 by viewModel.liveSpO2.collectAsStateWithLifecycle()
    LiveSensorActions(
        liveHr = liveHr,
        liveSpO2 = liveSpO2,
        onClick = onNavigateToSettings
    )
}

/**
 * Reusable stats icon button (📊) for navigating to history/stats screens.
 * Matches the style used in MeditationScreen.
 */
@Composable
fun StatsIconButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Text(
            "📊",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.grayscale()
        )
    }
}

/**
 * Combined actions bar that shows:
 * - Stats icon button (left)
 * - Sensor readings OR Settings button (right, mutually exclusive)
 *
 * @param onStatsClick Click handler for the stats icon
 * @param onSettingsClick Click handler for settings navigation
 * @param liveHr Optional live heart rate reading
 * @param liveSpO2 Optional live SpO₂ reading
 */
@Composable
fun StatsAndSensorActions(
    onStatsClick: () -> Unit,
    onSettingsClick: () -> Unit,
    liveHr: Int?,
    liveSpO2: Int?
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Stats icon (always shown)
        StatsIconButton(onClick = onStatsClick)
        
        // Sensor readings OR Settings button (mutually exclusive)
        if (liveHr != null || liveSpO2 != null) {
            LiveSensorActions(
                liveHr = liveHr,
                liveSpO2 = liveSpO2,
                onClick = onSettingsClick
            )
        } else {
            IconButton(onClick = onSettingsClick) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Settings"
                )
            }
        }
    }
}

/**
 * Nav-based version of StatsAndSensorActions that uses NavController.
 *
 * @param onStatsClick Click handler for the stats icon (navigate to history)
 * @param navController NavController for settings navigation
 * @param liveHr Optional live heart rate reading
 * @param liveSpO2 Optional live SpO₂ reading
 */
@Composable
fun StatsAndSensorActionsNav(
    onStatsClick: () -> Unit,
    navController: NavController,
    liveHr: Int?,
    liveSpO2: Int?
) {
    StatsAndSensorActions(
        onStatsClick = onStatsClick,
        onSettingsClick = { navController.navigate(WagsRoutes.SETTINGS) },
        liveHr = liveHr,
        liveSpO2 = liveSpO2
    )
}
