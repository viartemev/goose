package com.goose.android.ui.screens

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanResult
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.goose.android.ble.GooseBLEManager
import com.goose.android.ui.theme.GooseAccent
import com.goose.android.ui.theme.GooseRecoveryGreen
import com.goose.android.ui.theme.GooseRecoveryRed
import com.goose.android.ui.theme.GooseRecoveryYellow
import com.goose.android.ui.theme.GooseSeparator
import com.goose.android.ui.theme.GooseSurfaceElevated
import com.goose.android.ui.theme.GooseTextPrimary
import com.goose.android.ui.theme.GooseTextSecondary
import com.goose.android.ui.theme.GooseTextTertiary

@Composable
fun HomeScreen(viewModel: HomeViewModel = hiltViewModel()) {
    val connectionState by viewModel.connectionState.collectAsState()
    val liveHR by viewModel.liveHeartRate.collectAsState()
    val scannedDevices by viewModel.scannedDevices.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val connectedDeviceName by viewModel.connectedDeviceName.collectAsState()

    val context = LocalContext.current

    fun hasPermissions() =
        GooseBLEManager.REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants.values.all { it }) viewModel.startScan()
        }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ConnectionCard(
            connectionState = connectionState,
            deviceName = connectedDeviceName,
            liveHR = liveHR,
            isScanning = isScanning,
            onScanClick = {
                if (hasPermissions()) {
                    viewModel.startScan()
                } else {
                    permissionLauncher.launch(GooseBLEManager.REQUIRED_PERMISSIONS)
                }
            },
            onStopScan = viewModel::stopScan,
            onDisconnect = viewModel::disconnect,
        )

        if (scannedDevices.isNotEmpty()) {
            ScannedDeviceList(
                devices = scannedDevices,
                onDeviceClick = { viewModel.connect(it) },
            )
        }
    }
}

@Composable
private fun ConnectionCard(
    connectionState: String,
    deviceName: String?,
    liveHR: Int?,
    isScanning: Boolean,
    onScanClick: () -> Unit,
    onStopScan: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val transitioning = connectionState in listOf("connecting", "discovering") || isScanning

    val (statusLabel, statusColor) =
        when {
            connectionState == "ready" -> "Connected" to GooseRecoveryGreen
            connectionState in listOf("connecting", "discovering") -> "Connecting…" to GooseRecoveryYellow
            isScanning -> "Scanning…" to GooseRecoveryYellow
            connectionState.contains("failed") || connectionState.contains("not found") ->
                "Error: $connectionState" to GooseRecoveryRed
            else -> "Disconnected" to GooseTextSecondary
        }

    val statusIcon =
        when {
            connectionState == "ready" -> Icons.Default.BluetoothConnected
            transitioning -> Icons.AutoMirrored.Filled.BluetoothSearching
            else -> Icons.Default.Bluetooth
        }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = GooseSurfaceElevated),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(statusIcon, contentDescription = null, tint = statusColor, modifier = Modifier.size(20.dp))
                Text(
                    text = statusLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = statusColor,
                    fontWeight = FontWeight.SemiBold,
                )
                if (transitioning) {
                    Spacer(Modifier.width(4.dp))
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = statusColor,
                    )
                }
            }

            if (deviceName != null) {
                Text(
                    text = deviceName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = GooseTextPrimary,
                )
            }

            if (liveHR != null) {
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "$liveHR",
                        style = MaterialTheme.typography.displaySmall,
                        color = GooseRecoveryRed,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "bpm",
                        style = MaterialTheme.typography.bodySmall,
                        color = GooseTextSecondary,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
            }

            when {
                connectionState == "ready" ->
                    TextButton(
                        onClick = onDisconnect,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Disconnect", color = GooseRecoveryRed)
                    }
                connectionState in listOf("connecting", "discovering") -> Unit
                isScanning ->
                    TextButton(
                        onClick = onStopScan,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Stop Scan", color = GooseTextSecondary)
                    }
                else ->
                    Button(
                        onClick = onScanClick,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = GooseAccent),
                    ) {
                        Text("Scan for WHOOP")
                    }
            }
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun ScannedDeviceList(
    devices: List<ScanResult>,
    onDeviceClick: (BluetoothDevice) -> Unit,
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = GooseSurfaceElevated),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            Text(
                text = "Found Devices",
                style = MaterialTheme.typography.labelMedium,
                color = GooseTextSecondary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            devices.forEachIndexed { index, result ->
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable { onDeviceClick(result.device) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            text = result.device.name ?: result.device.address,
                            style = MaterialTheme.typography.bodyMedium,
                            color = GooseTextPrimary,
                        )
                        Text(
                            text = result.device.address,
                            style = MaterialTheme.typography.bodySmall,
                            color = GooseTextSecondary,
                        )
                    }
                    Text(
                        text = "${result.rssi} dBm",
                        style = MaterialTheme.typography.bodySmall,
                        color = GooseTextTertiary,
                    )
                }
                if (index < devices.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = GooseSeparator,
                    )
                }
            }
        }
    }
}
