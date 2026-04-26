package de.aarondietz.beetmeister.ui.connection

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.aarondietz.beetmeister.beet.BeetConnectionPhase
import de.aarondietz.beetmeister.beet.BeetDiscoveredDevice
import de.aarondietz.beetmeister.beet.BeetRepositoryState
import de.aarondietz.beetmeister.ui.formatting.bondStateLabel

@Composable
internal fun ConnectionGate(
    state: BeetRepositoryState,
    permissionsPermanentlyDenied: Boolean,
    onRequestPermissions: () -> Unit,
    onRequestBluetooth: () -> Unit,
    onScan: () -> Unit,
    onConnect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isBusy = state.connection.phase in setOf(
        BeetConnectionPhase.Scanning,
        BeetConnectionPhase.Bonding,
        BeetConnectionPhase.Connecting,
        BeetConnectionPhase.DiscoveringServices,
        BeetConnectionPhase.Syncing,
    )
    val connectionAttemptActive = state.connection.phase in setOf(
        BeetConnectionPhase.Bonding,
        BeetConnectionPhase.Connecting,
        BeetConnectionPhase.DiscoveringServices,
        BeetConnectionPhase.Syncing,
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(colors = listOf(Color(0xFFE8F1E3), Color(0xFFF7F3E8))))
            .padding(24.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Canvas(modifier = Modifier.size(120.dp)) {
                drawCircle(Color(0xFF46663E))
                drawCircle(Color(0xFFD2E5B6), radius = size.minDimension * 0.3f)
                drawCircle(Color(0xFFB7D487), radius = size.minDimension * 0.45f, style = Stroke(width = 8f))
            }
            Text(
                text = "BeetMeister",
                style = MaterialTheme.typography.headlineLarge,
                color = Color(0xFF2B402A),
                modifier = Modifier.padding(top = 20.dp),
            )
            Text(
                text = state.connection.detail ?: "Waiting for a controller connection.",
                style = MaterialTheme.typography.bodyLarge,
                color = Color(0xFF4E6150),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp, bottom = 24.dp),
            )

            if (isBusy) {
                CircularProgressIndicator(color = Color(0xFF567D46))
                Spacer(modifier = Modifier.height(16.dp))
            }

            when (state.connection.phase) {
                BeetConnectionPhase.PermissionsRequired -> FilledTonalButton(onClick = onRequestPermissions) {
                    Text(if (permissionsPermanentlyDenied) "Open App Settings" else "Grant Bluetooth permissions")
                }

                BeetConnectionPhase.BluetoothDisabled -> FilledTonalButton(onClick = onRequestBluetooth) {
                    Text("Turn on Bluetooth")
                }

                BeetConnectionPhase.Idle,
                BeetConnectionPhase.Disconnected,
                BeetConnectionPhase.Error -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton(onClick = onScan) { Text("Scan") }
                    OutlinedButton(onClick = onScan) { Text("Retry") }
                }

                else -> Unit
            }

            Spacer(modifier = Modifier.height(24.dp))

            if (state.discoveredDevices.isEmpty()) {
                ElevatedCard(
                    colors = CardDefaults.elevatedCardColors(containerColor = Color(0xFFF6F0E5)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = when {
                            state.connection.phase == BeetConnectionPhase.PermissionsRequired ->
                                if (permissionsPermanentlyDenied) {
                                    "Bluetooth permission was denied. Enable it in app settings."
                                } else {
                                    "Bluetooth permission is needed to find your controller."
                                }
                            isBusy ->
                                "Working on the controller connection. Keep this screen open."
                            else ->
                                "No nearby controller discovered yet. Wake the BeetMeister controller and keep the screen open while scanning."
                        },
                        modifier = Modifier.padding(16.dp),
                        color = Color(0xFF5F6251),
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.discoveredDevices, key = { device -> device.address }) { device ->
                        DeviceCard(
                            device = device,
                            selected = state.selectedAddress == device.address && connectionAttemptActive,
                            onConnect = { onConnect(device.address) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceCard(device: BeetDiscoveredDevice, selected: Boolean, onConnect: () -> Unit) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onConnect),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (selected) Color(0xFFE8F1E3) else Color(0xFFFDFBF6),
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(device.name, fontWeight = FontWeight.SemiBold)
                    Text(device.address, style = MaterialTheme.typography.bodySmall)
                }
                Icon(Icons.Default.BluetoothSearching, contentDescription = null, tint = Color(0xFF59734E))
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "RSSI ${device.rssi} dBm  |  ${bondStateLabel(device.bondState)}",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF5D6658),
            )
            if (selected) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Connecting...",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF46663E),
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Button(onClick = onConnect) {
                Text(if (selected) "Connecting" else "Connect")
            }
        }
    }
}
