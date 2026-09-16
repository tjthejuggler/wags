package com.example.wags.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.wags.R
import com.example.wags.data.garmin.GarminConnectionState
import com.example.wags.domain.model.BleConnectionState
import com.example.wags.domain.model.DeviceType
import com.example.wags.domain.model.ScannedDevice
import com.example.wags.ui.theme.ButtonDanger
import com.example.wags.ui.theme.EcgCyan
import com.example.wags.ui.theme.ReadinessGreen
import com.example.wags.ui.theme.ReadinessOrange
import com.example.wags.ui.theme.ReadinessRed
import com.example.wags.ui.theme.SurfaceVariant
import com.example.wags.ui.theme.TextPrimary
import com.example.wags.ui.theme.TextSecondary

// ── Connected heart-rate sensor sub-section ───────────────────────────────────

@Composable
fun ConnectedDeviceSection(
    deviceState: BleConnectionState,
    onDisconnect: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SettingsSubSectionLabel("Heart-Rate Sensor")

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                val (statusText, statusColor) = when (deviceState) {
                    is BleConnectionState.Connected -> {
                        val typeName = when (deviceState.deviceType) {
                            DeviceType.POLAR_H10 -> "Polar H10"
                            DeviceType.POLAR_VERITY -> "Polar Verity Sense"
                            DeviceType.OXIMETER -> "Pulse Oximeter"
                            DeviceType.GENERIC_BLE -> "BLE Sensor"
                        }
                        "$typeName: ${deviceState.deviceName}" to ReadinessGreen
                    }
                    is BleConnectionState.Connecting -> "Connecting…" to ReadinessOrange
                    is BleConnectionState.Scanning -> "Scanning…" to EcgCyan
                    is BleConnectionState.Error -> "Error: ${deviceState.message}" to ReadinessRed
                    is BleConnectionState.Disconnected -> "Not connected" to TextSecondary
                }
                Text(statusText, style = MaterialTheme.typography.bodyMedium, color = statusColor)

                // Show capabilities when connected
                if (deviceState is BleConnectionState.Connected) {
                    val caps = deviceState.deviceType.capabilities.joinToString(", ") { it.name }
                    Text(
                        "Capabilities: $caps",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }
            if (deviceState is BleConnectionState.Connected) {
                OutlinedButton(
                    onClick = onDisconnect,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
                ) { Text("Disconnect") }
            }
        }
    }
}

// ── Garmin watch sub-section ──────────────────────────────────────────────────

@Composable
fun GarminWatchSection(
    garminState: GarminConnectionState,
    onManage: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SettingsSubSectionLabel("Garmin Watch")

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_watch),
                    contentDescription = "Watch",
                    tint = TextSecondary
                )
                val (statusText, statusColor) = when (val gs = garminState) {
                    is GarminConnectionState.Connected ->
                        "Connected: ${gs.deviceName}" to ReadinessGreen
                    is GarminConnectionState.Initializing,
                    is GarminConnectionState.SdkReady ->
                        "Connecting…" to ReadinessOrange
                    is GarminConnectionState.DeviceFound ->
                        "Found: ${gs.deviceName}…" to ReadinessOrange
                    is GarminConnectionState.WagsAppNotFound ->
                        "WAGS app not found on ${gs.deviceName}" to ButtonDanger
                    is GarminConnectionState.Error ->
                        "Error" to ButtonDanger
                    is GarminConnectionState.Uninitialized ->
                        "Not connected" to TextSecondary
                }
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = statusColor
                )
            }
            Button(
                onClick = onManage,
                colors = ButtonDefaults.buttonColors(
                    containerColor = SurfaceVariant,
                    contentColor = TextPrimary
                )
            ) {
                Text(
                    if (garminState is GarminConnectionState.Connected) "Manage" else "Setup"
                )
            }
        }
    }
}

// ── Nearby sensors scan sub-section ───────────────────────────────────────────

/**
 * Preferred sensors (Polar H10, O2Ring-style oximeters) are shown directly;
 * everything else the phone discovers is collapsed behind a "Show all
 * devices" toggle so the list stays focused on the devices actually used.
 */
@Composable
fun NearbySensorsSection(
    isScanning: Boolean,
    scanResults: List<ScannedDevice>,
    deviceState: BleConnectionState,
    showAllDevices: Boolean,
    onToggleShowAll: () -> Unit,
    onScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnect: (ScannedDevice) -> Unit
) {
    val preferred = scanResults.filter { DeviceType.isPreferred(it.name) }
    val other = scanResults.filterNot { DeviceType.isPreferred(it.name) }
        .sortedByDescending { it.rssi }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SettingsSubSectionLabel("Nearby Sensors")
            if (isScanning) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = EcgCyan,
                        strokeWidth = 2.dp
                    )
                    OutlinedButton(
                        onClick = onStopScan,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
                    ) {
                        Text("Stop")
                    }
                }
            } else {
                Button(onClick = onScan) {
                    Text("Scan")
                }
            }
        }

        // Empty state
        if (scanResults.isEmpty() && !isScanning) {
            Text(
                "No sensors found. Make sure your H10 / O2Ring is powered on, then tap Scan.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }

        // Preferred sensors first (H10, O2Ring) — always visible
        preferred.forEach { device ->
            DeviceResultRow(
                device = device,
                deviceState = deviceState,
                onConnect = { onConnect(device) }
            )
        }

        // Everything else — collapsed behind a "Show all" toggle
        if (other.isNotEmpty() && !showAllDevices) {
            OutlinedButton(
                onClick = onToggleShowAll,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary)
            ) {
                Text("Show all devices (${other.size})")
            }
        }
        if (showAllDevices) {
            other.forEach { device ->
                DeviceResultRow(
                    device = device,
                    deviceState = deviceState,
                    onConnect = { onConnect(device) }
                )
            }
            if (other.isNotEmpty()) {
                OutlinedButton(
                    onClick = onToggleShowAll,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary)
                ) {
                    Text("Show preferred sensors only")
                }
            }
        }
    }
}

@Composable
private fun DeviceResultRow(
    device: ScannedDevice,
    deviceState: BleConnectionState,
    onConnect: () -> Unit
) {
    val isConnected = deviceState is BleConnectionState.Connected &&
        deviceState.deviceId == device.identifier
    val isConnecting = deviceState is BleConnectionState.Connecting &&
        deviceState.deviceId == device.identifier

    Surface(
        color = if (isConnected) SurfaceVariant else Color.Transparent,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.name.ifBlank { device.identifier },
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary
                )
                Text(
                    text = device.identifier,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                when {
                    isConnected -> Text(
                        "Connected",
                        style = MaterialTheme.typography.bodySmall,
                        color = ReadinessGreen
                    )
                    isConnecting -> Text(
                        "Connecting…",
                        style = MaterialTheme.typography.bodySmall,
                        color = ReadinessOrange
                    )
                }
            }
            when {
                isConnected -> { /* no button — disconnect from the section above */ }
                isConnecting -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = EcgCyan,
                        strokeWidth = 2.dp
                    )
                }
                else -> {
                    Button(
                        onClick = onConnect,
                        modifier = Modifier.height(32.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                    ) { Text("Connect", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}
