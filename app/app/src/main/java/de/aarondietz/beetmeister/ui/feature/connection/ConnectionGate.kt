package de.aarondietz.beetmeister.ui.feature.connection

import android.util.Log
import androidx.compose.foundation.background
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
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.aarondietz.beetmeister.R
import de.aarondietz.beetmeister.model.connection.BeetConnectionPhase
import de.aarondietz.beetmeister.model.repository.BeetRepositoryState
import de.aarondietz.beetmeister.model.update.BeetMaintenanceUpdatePhase
import de.aarondietz.beetmeister.strings.rememberBeetStringResolver
import de.aarondietz.beetmeister.ui.core.component.BeetMeisterLogo
import de.aarondietz.beetmeister.ui.core.formatting.maintenanceImageKindLabel

private const val MAINTENANCE_PANEL_TAG = "MaintenanceUpdatePanel"

@Composable
internal fun ConnectionGate(
    state: BeetRepositoryState,
    permissionsPermanentlyDenied: Boolean,
    onRequestPermissions: () -> Unit,
    onRequestBluetooth: () -> Unit,
    onScan: () -> Unit,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
    onPrepareBundledFirmware: () -> Unit,
    onPickCustomFirmware: () -> Unit,
    onStartMaintenanceUpdate: () -> Unit,
    onAbortMaintenanceUpdate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = rememberBeetStringResolver()
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
            BeetMeisterLogo(modifier = Modifier.size(120.dp))
            Text(
                text = strings.get(R.string.app_name),
                style = MaterialTheme.typography.headlineLarge,
                color = Color(0xFF2B402A),
                modifier = Modifier.padding(top = 20.dp),
            )
            Text(
                text = state.connection.detail ?: strings.get(R.string.connection_waiting_for_controller),
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
                    Text(
                        strings.get(
                            if (permissionsPermanentlyDenied) {
                                R.string.connection_open_app_settings
                            } else {
                                R.string.connection_grant_bluetooth_permissions
                            },
                        ),
                    )
                }

                BeetConnectionPhase.BluetoothDisabled -> FilledTonalButton(onClick = onRequestBluetooth) {
                    Text(strings.get(R.string.connection_turn_on_bluetooth))
                }

                BeetConnectionPhase.Idle,
                BeetConnectionPhase.Disconnected,
                BeetConnectionPhase.Error -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton(onClick = onScan) { Text(strings.get(R.string.connection_scan)) }
                    OutlinedButton(onClick = onScan) { Text(strings.get(R.string.connection_retry)) }
                }

                BeetConnectionPhase.MaintenanceRequired -> Unit

                else -> Unit
            }

            Spacer(modifier = Modifier.height(24.dp))

            if (state.connection.phase == BeetConnectionPhase.MaintenanceRequired) {
                MaintenanceUpdatePanel(
                    state = state,
                    onPrepareBundledFirmware = onPrepareBundledFirmware,
                    onPickCustomFirmware = onPickCustomFirmware,
                    onStartMaintenanceUpdate = onStartMaintenanceUpdate,
                    onAbortMaintenanceUpdate = onAbortMaintenanceUpdate,
                    onDisconnect = onDisconnect,
                    showDisconnectButton = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else if (state.discoveredDevices.isEmpty()) {
                ElevatedCard(
                    colors = CardDefaults.elevatedCardColors(containerColor = Color(0xFFF6F0E5)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = when {
                            state.connection.phase == BeetConnectionPhase.PermissionsRequired ->
                                if (permissionsPermanentlyDenied) {
                                    strings.get(R.string.connection_permission_denied_card)
                                } else {
                                    strings.get(R.string.connection_permission_needed_card)
                                }
                            isBusy ->
                                strings.get(R.string.connection_busy_card)
                            else ->
                                strings.get(R.string.connection_no_devices_card)
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
internal fun MaintenanceUpdatePanel(
    state: BeetRepositoryState,
    onPrepareBundledFirmware: () -> Unit,
    onPickCustomFirmware: () -> Unit,
    onStartMaintenanceUpdate: () -> Unit,
    onAbortMaintenanceUpdate: () -> Unit,
    onDisconnect: () -> Unit,
    showDisconnectButton: Boolean,
    modifier: Modifier = Modifier,
) {
    val strings = rememberBeetStringResolver()
    val info = state.maintenanceInfo
    val update = state.maintenanceUpdate
    val selected = update.selectedFirmware
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = Color(0xFFF6F0E5)),
        modifier = modifier.testTag(MaintenanceUpdateTestTags.Card),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                strings.get(R.string.maintenance_title),
                style = MaterialTheme.typography.titleLarge,
                color = Color(0xFF2B402A),
                modifier = Modifier.testTag(MaintenanceUpdateTestTags.Title),
            )
            if (info != null) {
                Text(
                    text = strings.get(
                        R.string.maintenance_current_firmware,
                        info.firmwareVersion,
                        info.buildLabel,
                        maintenanceImageKindLabel(info.imageKind, strings),
                    ),
                    color = Color(0xFF4E6150),
                    modifier = Modifier.testTag(MaintenanceUpdateTestTags.CurrentFirmware),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(
                    onClick = onPrepareBundledFirmware,
                    modifier = Modifier.testTag(MaintenanceUpdateTestTags.BundledButton),
                ) {
                    Text(strings.get(R.string.maintenance_use_bundled))
                }
                OutlinedButton(
                    onClick = onPickCustomFirmware,
                    modifier = Modifier.testTag(MaintenanceUpdateTestTags.CustomButton),
                ) {
                    Text(strings.get(R.string.maintenance_choose_custom))
                }
            }
            if (selected != null) {
                Text(
                    text = strings.get(
                        R.string.maintenance_selected_summary,
                        selected.metadata.firmwareVersion,
                        selected.metadata.buildLabel,
                        selected.sourceLabel,
                    ),
                    color = Color(0xFF4E6150),
                    modifier = Modifier.testTag(MaintenanceUpdateTestTags.Summary),
                )
                Text(
                    text = strings.get(
                        R.string.maintenance_selected_details,
                        selected.metadata.productId,
                        selected.metadata.hardwareRev,
                        selected.imageSize,
                    ),
                    color = Color(0xFF4E6150),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag(MaintenanceUpdateTestTags.Details),
                )
                if (selected.isDowngrade) {
                    Text(
                        strings.get(R.string.maintenance_warning_downgrade),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag(MaintenanceUpdateTestTags.DowngradeWarning),
                    )
                }
                if (selected.runtimeProtocolWarning) {
                    Text(
                        strings.get(R.string.maintenance_warning_runtime_protocol),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag(MaintenanceUpdateTestTags.RuntimeWarning),
                    )
                }
            }
            update.statusDetail?.let { detail ->
                Text(
                    detail,
                    color = Color(0xFF4E6150),
                    modifier = Modifier.testTag(MaintenanceUpdateTestTags.StatusDetail),
                )
            }
            update.errorDetail?.let { detail ->
                Text(
                    detail,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag(MaintenanceUpdateTestTags.ErrorDetail),
                )
            }
            when (update.phase) {
                BeetMaintenanceUpdatePhase.Uploading,
                BeetMaintenanceUpdatePhase.Reconnecting,
                BeetMaintenanceUpdatePhase.Rebooting -> {
                    CircularProgressIndicator(
                        color = Color(0xFF567D46),
                        modifier = Modifier.testTag(MaintenanceUpdateTestTags.Progress),
                    )
                    OutlinedButton(
                        onClick = onAbortMaintenanceUpdate,
                        modifier = Modifier.testTag(MaintenanceUpdateTestTags.AbortButton),
                    ) {
                        Text(strings.get(R.string.maintenance_abort_update))
                    }
                }

                BeetMaintenanceUpdatePhase.Ready,
                BeetMaintenanceUpdatePhase.Failed,
                BeetMaintenanceUpdatePhase.Idle,
                BeetMaintenanceUpdatePhase.Completed -> {
                    FilledTonalButton(
                        onClick = {
                            Log.d(
                                MAINTENANCE_PANEL_TAG,
                                "Install button clicked phase=${update.phase} " +
                                    "selected=${selected?.metadata?.firmwareVersion}/${selected?.sourceLabel}",
                            )
                            onStartMaintenanceUpdate()
                        },
                        enabled = selected != null,
                        modifier = Modifier.testTag(MaintenanceUpdateTestTags.InstallButton),
                    ) {
                        Text(strings.get(R.string.maintenance_install_firmware))
                    }
                }
            }
            if (showDisconnectButton) {
                OutlinedButton(
                    onClick = onDisconnect,
                    modifier = Modifier.testTag(MaintenanceUpdateTestTags.DisconnectButton),
                ) {
                    Text(strings.get(R.string.common_disconnect))
                }
            }
        }
    }
}

