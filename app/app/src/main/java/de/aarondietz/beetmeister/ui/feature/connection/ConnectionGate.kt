package de.aarondietz.beetmeister.ui.feature.connection

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.aarondietz.beetmeister.R
import de.aarondietz.beetmeister.model.connection.BeetConnectionPhase
import de.aarondietz.beetmeister.model.repository.BeetRepositoryState
import de.aarondietz.beetmeister.model.update.BeetFirmwareSource
import de.aarondietz.beetmeister.model.update.BeetMaintenanceUpdatePhase
import de.aarondietz.beetmeister.model.update.isActiveMaintenancePhase
import de.aarondietz.beetmeister.strings.rememberBeetStringResolver
import de.aarondietz.beetmeister.ui.core.component.BeetMeisterLogo
import de.aarondietz.beetmeister.ui.core.formatting.formatDuration
import de.aarondietz.beetmeister.ui.core.formatting.maintenanceImageKindLabel

private const val MAINTENANCE_PANEL_TAG = "MaintenanceUpdatePanel"
private val MaintenanceActionBreakpoint = 280.dp

private fun selectionSourceLabel(
    source: BeetFirmwareSource,
    strings: de.aarondietz.beetmeister.strings.BeetStringResolver,
): String = when (source) {
    BeetFirmwareSource.Bundled -> strings.get(R.string.maintenance_selection_source_bundled)
    BeetFirmwareSource.Custom -> strings.get(R.string.maintenance_selection_source_custom)
}

private fun selectedMatchesInstalled(
    selected: de.aarondietz.beetmeister.model.update.BeetFirmwarePackageSummary?,
    info: de.aarondietz.beetmeister.model.controller.BeetMaintenanceInfo?,
): Boolean {
    if (selected == null || info == null) {
        return false
    }
    val metadata = selected.metadata
    return metadata.firmwareVersion == info.firmwareVersion &&
        metadata.buildLabel == info.buildLabel &&
        metadata.imageKind == info.imageKind
}

private fun maintenanceDetailText(label: String, value: String): String = "$label: $value"

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

            if (state.discoveredDevices.isEmpty()) {
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
internal fun MaintenanceScreen(
    state: BeetRepositoryState,
    forcedMode: Boolean,
    onClose: (() -> Unit)?,
    onPrepareBundledFirmware: () -> Unit,
    onPickCustomFirmware: () -> Unit,
    onStartMaintenanceUpdate: () -> Unit,
    onAbortMaintenanceUpdate: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = rememberBeetStringResolver()
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(colors = listOf(Color(0xFFF2EFE8), Color(0xFFE8F1E3))))
            .padding(16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = strings.get(R.string.maintenance_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color(0xFF2B402A),
                )
                if (onClose != null) {
                    TextButton(onClick = onClose) {
                        Text(strings.get(R.string.common_back))
                    }
                }
            }
            ElevatedCard(
                colors = CardDefaults.elevatedCardColors(containerColor = Color(0xFFF6F0E5)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = if (forcedMode) {
                            strings.get(R.string.maintenance_screen_forced_intro)
                        } else {
                            strings.get(R.string.maintenance_screen_optional_intro)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF4E6150),
                    )
                    state.connection.detail?.let { detail ->
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF4E6150),
                        )
                    }
                }
            }
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
    val selectedAlreadyInstalled = selectedMatchesInstalled(selected, info)
    val updateActive = update.phase.isActiveMaintenancePhase()
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
            FirmwareSourceActions(
                onPrepareBundledFirmware = onPrepareBundledFirmware,
                onPickCustomFirmware = onPickCustomFirmware,
                enabled = !updateActive,
            )
            if (selected != null) {
                HorizontalDivider(color = Color(0xFFD7D1C6))
                Text(
                    text = strings.get(R.string.maintenance_selected_header),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(0xFF2B402A),
                )
                MaintenanceDetailLine(
                    label = strings.get(R.string.maintenance_selected_version_label),
                    value = "${selected.metadata.firmwareVersion} (${selected.metadata.buildLabel})",
                    modifier = Modifier.testTag(MaintenanceUpdateTestTags.Summary),
                )
                MaintenanceDetailLine(
                    label = strings.get(R.string.maintenance_selected_target_label),
                    value = strings.get(
                        R.string.maintenance_selected_details_value,
                        selected.metadata.productId,
                        selected.metadata.hardwareRev,
                        selected.imageSize,
                    ),
                    modifier = Modifier.testTag(MaintenanceUpdateTestTags.Details),
                )
                MaintenanceDetailLine(
                    label = strings.get(R.string.maintenance_selected_source_label),
                    value = selectionSourceLabel(selected.source, strings),
                )
                MaintenanceDetailLine(
                    label = strings.get(R.string.maintenance_selected_file_label),
                    value = selected.sourceLabel,
                )
                MaintenanceDetailLine(
                    label = strings.get(R.string.maintenance_selected_image_kind_label),
                    value = maintenanceImageKindLabel(selected.metadata.imageKind, strings),
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
            if (updateActive && update.totalBytes > 0
            ) {
                val progressPercent = ((update.bytesTransferred.toDouble() / update.totalBytes.toDouble()) * 100.0)
                    .toInt()
                    .coerceIn(0, 100)
                Text(
                    strings.get(R.string.maintenance_progress_percent, progressPercent),
                    color = Color(0xFF4E6150),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    strings.get(
                        R.string.maintenance_progress_elapsed,
                        formatDuration(update.elapsedSeconds, strings),
                    ),
                    color = Color(0xFF4E6150),
                    style = MaterialTheme.typography.bodySmall,
                )
                update.estimatedRemainingSeconds?.let { remaining ->
                    Text(
                        strings.get(
                            R.string.maintenance_progress_remaining,
                            formatDuration(remaining, strings),
                        ),
                        color = Color(0xFF4E6150),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            update.errorDetail?.let { detail ->
                Text(
                    detail,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag(MaintenanceUpdateTestTags.ErrorDetail),
                )
            }
            when (update.phase) {
                BeetMaintenanceUpdatePhase.Starting,
                BeetMaintenanceUpdatePhase.Uploading,
                BeetMaintenanceUpdatePhase.Reconnecting,
                BeetMaintenanceUpdatePhase.Rebooting -> {
                    CircularProgressIndicator(
                        color = Color(0xFF567D46),
                        modifier = Modifier.testTag(MaintenanceUpdateTestTags.Progress),
                    )
                    OutlinedButton(
                        onClick = onAbortMaintenanceUpdate,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(MaintenanceUpdateTestTags.AbortButton),
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
                        enabled = selected != null && !selectedAlreadyInstalled,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(MaintenanceUpdateTestTags.InstallButton),
                    ) {
                        Text(
                            strings.get(
                                if (selectedAlreadyInstalled) {
                                    R.string.maintenance_selected_already_installed
                                } else {
                                    R.string.maintenance_install_firmware
                                },
                            ),
                        )
                    }
                }
            }
            if (showDisconnectButton) {
                HorizontalDivider(color = Color(0xFFD7D1C6))
                OutlinedButton(
                    onClick = onDisconnect,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(MaintenanceUpdateTestTags.DisconnectButton),
                ) {
                    Text(strings.get(R.string.common_disconnect))
                }
            }
        }
    }
}

@Composable
private fun FirmwareSourceActions(
    onPrepareBundledFirmware: () -> Unit,
    onPickCustomFirmware: () -> Unit,
    enabled: Boolean,
) {
    val strings = rememberBeetStringResolver()
    BoxWithConstraints {
        val stacked = maxWidth < MaintenanceActionBreakpoint
        if (stacked) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                FilledTonalButton(
                    onClick = onPrepareBundledFirmware,
                    enabled = enabled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(MaintenanceUpdateTestTags.BundledButton),
                ) {
                    Text(
                        text = strings.get(R.string.maintenance_use_bundled),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
                OutlinedButton(
                    onClick = onPickCustomFirmware,
                    enabled = enabled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(MaintenanceUpdateTestTags.CustomButton),
                ) {
                    Text(
                        text = strings.get(R.string.maintenance_choose_custom),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FilledTonalButton(
                    onClick = onPrepareBundledFirmware,
                    enabled = enabled,
                    modifier = Modifier
                        .weight(1f)
                        .testTag(MaintenanceUpdateTestTags.BundledButton),
                ) {
                    Text(
                        text = strings.get(R.string.maintenance_use_bundled),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
                OutlinedButton(
                    onClick = onPickCustomFirmware,
                    enabled = enabled,
                    modifier = Modifier
                        .weight(1f)
                        .testTag(MaintenanceUpdateTestTags.CustomButton),
                ) {
                    Text(
                        text = strings.get(R.string.maintenance_choose_custom),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun MaintenanceDetailLine(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = maintenanceDetailText(label, value),
        color = Color(0xFF4E6150),
        style = MaterialTheme.typography.bodyMedium,
        modifier = modifier,
    )
}

