package de.aarondietz.beetmeister.ui.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import de.aarondietz.beetmeister.R
import de.aarondietz.beetmeister.model.connection.BeetConnectionPhase
import de.aarondietz.beetmeister.model.controller.BeetValveConfig
import de.aarondietz.beetmeister.model.repository.BeetRepositoryState
import de.aarondietz.beetmeister.strings.rememberBeetStringResolver
import de.aarondietz.beetmeister.ui.core.component.BeetPullToRefreshBox
import de.aarondietz.beetmeister.ui.core.component.SettingInfoDialog
import de.aarondietz.beetmeister.ui.core.component.ValueGridRow
import de.aarondietz.beetmeister.ui.core.formatting.connectionPhaseLabel
import de.aarondietz.beetmeister.ui.core.formatting.formatDuration
import de.aarondietz.beetmeister.ui.core.formatting.maintenanceImageKindLabel
import de.aarondietz.beetmeister.ui.core.formatting.valveStateLabel

internal data class SettingsSaveDraft(
    val valveConfig: BeetValveConfig?,
    val wateringIntervalSeconds: Int?,
    val maxActivePumps: Int?,
)

@Composable
internal fun SettingsScreen(
    state: BeetRepositoryState,
    onRefreshValveConfig: () -> Unit,
    onRefreshWateringInterval: () -> Unit,
    onRefreshMaxActivePumps: () -> Unit,
    onSaveValveConfig: (BeetValveConfig) -> Unit,
    onSaveWateringInterval: (Int) -> Unit,
    onStoreMaxActivePumps: (Int) -> Unit,
    onOpenValveCalibration: () -> Unit,
    onOpenValve: () -> Unit,
    onCloseValve: () -> Unit,
    onRebootController: () -> Unit,
    onRunScheduler: () -> Unit,
    onFactoryResetController: () -> Unit,
    onOpenFirmwareUpdate: () -> Unit,
    onDisconnect: () -> Unit,
    onUnsavedStateChange: (Boolean, SettingsSaveDraft?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = rememberBeetStringResolver()
    val info = state.controllerInfo
    val maintenanceInfo = state.maintenanceInfo
    val valveConfig = state.valveConfig
    val deviceState = state.deviceState
    var valveEnabled by remember { mutableStateOf(false) }
    var moveDurationText by remember { mutableStateOf("") }
    var settleDelayText by remember { mutableStateOf("") }
    var openHoldText by remember { mutableStateOf("") }
    var intervalHoursText by remember { mutableStateOf("") }
    var intervalMinutesText by remember { mutableStateOf("") }
    var maxPumpsDraft by remember { mutableStateOf<Int?>(null) }
    var activeInfo by remember { mutableStateOf<ValveSettingInfo?>(null) }
    var userEditedMoveDuration by remember { mutableStateOf(false) }
    var userEditedSettleDelay by remember { mutableStateOf(false) }
    var userEditedOpenHold by remember { mutableStateOf(false) }
    var showRebootDialog by remember { mutableStateOf(false) }
    var showFactoryResetDialog by remember { mutableStateOf(false) }

    LaunchedEffect(state.connection.phase) {
        if (state.connection.phase == BeetConnectionPhase.Connected) {
            onRefreshValveConfig()
            onRefreshWateringInterval()
            onRefreshMaxActivePumps()
        }
    }

    LaunchedEffect(valveConfig) {
        if (valveConfig != null) {
            android.util.Log.d("Settings", "LaunchedEffect(valveConfig) firing: " +
                "userEditedMoveDuration=$userEditedMoveDuration " +
                "userEditedSettleDelay=$userEditedSettleDelay " +
                "userEditedOpenHold=$userEditedOpenHold " +
                "vc.moveDuration=${valveConfig.moveDurationMillis}")
            if (!userEditedMoveDuration) {
                moveDurationText = valveConfig.moveDurationMillis.toString()
            }
            if (!userEditedSettleDelay) {
                settleDelayText = valveConfig.settleDelayMillis.toString()
            }
            if (!userEditedOpenHold) {
                openHoldText = valveConfig.openHoldMillis.toString()
            }
            valveEnabled = valveConfig.valveEnabled
        }
    }

    LaunchedEffect(state.wateringInterval) {
        val interval = state.wateringInterval ?: return@LaunchedEffect
        intervalHoursText = (interval.seconds / 3600).toString()
        intervalMinutesText = ((interval.seconds % 3600) / 60).toString()
    }

    LaunchedEffect(state.maxActivePumps) {
        val current = state.maxActivePumps ?: return@LaunchedEffect
        maxPumpsDraft = current
    }

    val editedValveConfig = parseValveConfig(
        base = valveConfig,
        valveEnabled = valveEnabled,
        moveDurationText = moveDurationText,
        settleDelayText = settleDelayText,
        openHoldText = openHoldText,
    )
    val valveConfigDirty = valveConfig?.let {
        valveEnabled != it.valveEnabled ||
            moveDurationText != it.moveDurationMillis.toString() ||
            settleDelayText != it.settleDelayMillis.toString() ||
            openHoldText != it.openHoldMillis.toString()
    } == true
    val valveConfigChanged = valveConfig != null && valveConfigDirty
    val moveDurationError = moveDurationValidationMessage(moveDurationText, strings)
    val settleDelayError = settleDelayValidationMessage(settleDelayText, strings)
    val openHoldError = openHoldValidationMessage(openHoldText, strings)
    val wateringIntervalSeconds = parseWateringIntervalSeconds(intervalHoursText, intervalMinutesText)
    val wateringIntervalDirty = state.wateringInterval?.let {
        intervalHoursText != (it.seconds / 3600).toString() ||
            intervalMinutesText != ((it.seconds % 3600) / 60).toString()
    } == true
    val wateringIntervalChanged = state.wateringInterval != null && wateringIntervalDirty
    val wateringIntervalError = wateringIntervalValidationMessage(intervalHoursText, intervalMinutesText, strings)
    val showWateringIntervalError = state.wateringInterval != null && wateringIntervalError != null
    val maxPumpsDirty = maxPumpsDraft != null && maxPumpsDraft != state.maxActivePumps
    val valveMotionActive = deviceState?.valveState == "OPENING" || deviceState?.valveState == "CLOSING"
    val valveManualControlEnabled =
        state.connection.phase == BeetConnectionPhase.Connected &&
            valveConfig?.valveEnabled == true &&
            deviceState?.activePumps == 0 &&
            !valveMotionActive
    val hasUnsavedChanges = valveConfigDirty || wateringIntervalDirty || maxPumpsDirty
    val savableDraft = if (!hasUnsavedChanges) {
        null
    } else if ((valveConfigDirty && editedValveConfig == null) || (wateringIntervalDirty && wateringIntervalSeconds == null) || (maxPumpsDirty && maxPumpsDraft == null)) {
        null
    } else {
        SettingsSaveDraft(
            valveConfig = if (valveConfigDirty) editedValveConfig else null,
            wateringIntervalSeconds = if (wateringIntervalDirty) wateringIntervalSeconds else null,
            maxActivePumps = if (maxPumpsDirty) maxPumpsDraft else null,
        )
    }

    activeInfo?.let { info ->  
        SettingInfoDialog(
            title = info.title(strings),
            body = info.body(strings),
            confirmLabel = strings.get(R.string.common_close),
            onDismiss = { activeInfo = null },
        )
    }

    if (showRebootDialog) {
        AlertDialog(
            onDismissRequest = { showRebootDialog = false },
            title = { Text(strings.get(R.string.settings_reboot_dialog_title)) },
            text = { Text(strings.get(R.string.settings_reboot_dialog_body)) },
            dismissButton = {
                TextButton(onClick = { showRebootDialog = false }) {
                    Text(strings.get(R.string.common_cancel))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRebootDialog = false
                        onRebootController()
                    },
                ) {
                    Text(strings.get(R.string.settings_reboot_action))
                }
            },
        )
    }

    if (showFactoryResetDialog) {
        AlertDialog(
            onDismissRequest = { showFactoryResetDialog = false },
            title = { Text(strings.get(R.string.settings_factory_reset_dialog_title)) },
            text = { Text(strings.get(R.string.settings_factory_reset_dialog_body)) },
            dismissButton = {
                TextButton(onClick = { showFactoryResetDialog = false }) {
                    Text(strings.get(R.string.common_cancel))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showFactoryResetDialog = false
                        onFactoryResetController()
                    },
                ) {
                    Text(strings.get(R.string.settings_factory_reset_action))
                }
            },
        )
    }

    LaunchedEffect(hasUnsavedChanges, savableDraft) {
        onUnsavedStateChange(hasUnsavedChanges, savableDraft)
    }

    BeetPullToRefreshBox(
        isRefreshing = state.valveConfigRefreshing || state.wateringIntervalRefreshing,
        onRefresh = {
            onRefreshValveConfig()
            onRefreshWateringInterval()
        },
        enabled = state.connection.phase == BeetConnectionPhase.Connected,
        modifier = modifier.testTag(SettingsTestTags.Container),
    ) {
        LazyColumn(
            modifier = Modifier.testTag(SettingsTestTags.List),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(SettingsTestTags.ControllerInfoCard),
                    colors = CardDefaults.elevatedCardColors(containerColor = Color(0xFFF2EFE8)),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(strings.get(R.string.settings_title), style = MaterialTheme.typography.headlineSmall)
                        Spacer(modifier = Modifier.height(12.dp))
                        ValueGridRow(
                            strings.get(R.string.settings_label_device_id),
                            info?.deviceId ?: maintenanceInfo?.productId ?: strings.get(R.string.placeholder_dash),
                            strings.get(R.string.settings_label_protocol),
                            info?.protocolVersion?.toString()
                                ?: maintenanceInfo?.runtimeProtocolVersion?.toString()
                                ?: strings.get(R.string.placeholder_dash),
                            leftValueModifier = Modifier.testTag(SettingsTestTags.ControllerInfoDeviceId),
                            rightValueModifier = Modifier.testTag(SettingsTestTags.ControllerInfoProtocolVersion),
                        )
                        ValueGridRow(
                            strings.get(R.string.settings_label_firmware),
                            info?.firmwareVersion ?: maintenanceInfo?.firmwareVersion ?: strings.get(R.string.placeholder_dash),
                            strings.get(R.string.settings_label_pairs),
                            info?.pairCount?.toString() ?: strings.get(R.string.placeholder_dash),
                            leftValueModifier = Modifier.testTag(SettingsTestTags.ControllerInfoFirmwareVersion),
                            rightValueModifier = Modifier.testTag(SettingsTestTags.ControllerInfoPairCount),
                        )
                        if (maintenanceInfo != null) {
                            ValueGridRow(
                                strings.get(R.string.settings_label_build),
                                maintenanceInfo.buildLabel,
                                strings.get(R.string.settings_label_firmware_source),
                                maintenanceImageKindLabel(maintenanceInfo.imageKind, strings),
                                leftValueModifier = Modifier.testTag(SettingsTestTags.ControllerInfoBuildLabel),
                            )
                        }
                        ValueGridRow(
                            strings.get(R.string.settings_label_connection),
                            connectionPhaseLabel(state.connection.phase, strings),
                            strings.get(R.string.settings_label_address),
                            state.selectedAddress ?: strings.get(R.string.placeholder_dash),
                            leftValueModifier = Modifier.testTag(SettingsTestTags.ControllerInfoConnectionPhase),
                            rightValueModifier = Modifier.testTag(SettingsTestTags.ControllerInfoAddress),
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = onDisconnect,
                            modifier = Modifier.testTag(SettingsTestTags.ControllerInfoDisconnect),
                        ) {
                            Text(strings.get(R.string.common_disconnect))
                        }
                    }
                }
            }
            item {
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(SettingsTestTags.FirmwareUpdateCard),
                    colors = CardDefaults.elevatedCardColors(containerColor = Color(0xFFF6F0E5)),
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(strings.get(R.string.settings_firmware_update_title), style = MaterialTheme.typography.titleMedium)
                        Text(strings.get(R.string.settings_firmware_update_subtitle), style = MaterialTheme.typography.bodyMedium)
                        Button(
                            onClick = onOpenFirmwareUpdate,
                            modifier = Modifier.testTag(SettingsTestTags.FirmwareUpdateOpenButton),
                        ) {
                            Text(strings.get(R.string.settings_open_firmware_update))
                        }
                    }
                }
            }
            item {
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(SettingsTestTags.ControllerManagementCard),
                    colors = CardDefaults.elevatedCardColors(containerColor = Color(0xFFF5E9E5)),
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(strings.get(R.string.settings_controller_management_title), style = MaterialTheme.typography.titleMedium)
                        Text(strings.get(R.string.settings_controller_management_subtitle), style = MaterialTheme.typography.bodyMedium)
                        Button(
                            onClick = { showRebootDialog = true },
                            enabled = state.connection.phase == BeetConnectionPhase.Connected && !hasUnsavedChanges,
                            modifier = Modifier.testTag(SettingsTestTags.ControllerManagementReboot),
                        ) {
                            Text(strings.get(R.string.settings_reboot_action))
                        }
                        Button(
                            onClick = { showFactoryResetDialog = true },
                            enabled = state.connection.phase == BeetConnectionPhase.Connected && !hasUnsavedChanges,
                            modifier = Modifier.testTag(SettingsTestTags.ControllerManagementFactoryReset),
                        ) {
                            Text(strings.get(R.string.settings_factory_reset_action))
                        }
                        if (hasUnsavedChanges) {
                            Text(
                                text = strings.get(R.string.settings_controller_management_unsaved_hint),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
            item {
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(SettingsTestTags.WateringIntervalCard),
                    colors = CardDefaults.elevatedCardColors(containerColor = Color(0xFFEDEFF4)),
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(strings.get(R.string.settings_watering_interval_title), style = MaterialTheme.typography.titleMedium)
                        Text(strings.get(R.string.settings_watering_interval_subtitle), style = MaterialTheme.typography.bodyMedium)
                        ValueGridRow(
                            strings.get(R.string.settings_label_current_watering_interval),
                            state.wateringInterval?.let { formatDuration(it.seconds, strings) } ?: strings.get(R.string.placeholder_dash),
                            strings.get(R.string.settings_label_next_check),
                            deviceState?.let { formatDuration(it.nextCheckInSeconds, strings) } ?: strings.get(R.string.placeholder_dash),
                            leftValueModifier = Modifier.testTag(SettingsTestTags.WateringIntervalCurrent),
                            rightValueModifier = Modifier.testTag(SettingsTestTags.WateringIntervalNextCheck),
                        )
                        OutlinedTextField(
                            value = intervalHoursText,
                            onValueChange = { intervalHoursText = it.filter(Char::isDigit) },
                            label = { Text(strings.get(R.string.settings_label_interval_hours)) },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(SettingsTestTags.WateringIntervalHoursField),
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                        OutlinedTextField(
                            value = intervalMinutesText,
                            onValueChange = { intervalMinutesText = it.filter(Char::isDigit) },
                            label = { Text(strings.get(R.string.settings_label_interval_minutes)) },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(SettingsTestTags.WateringIntervalMinutesField),
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                        if (showWateringIntervalError) {
                            Text(
                                text = wateringIntervalError ?: "",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(
                                onClick = { wateringIntervalSeconds?.let(onSaveWateringInterval) },
                                enabled = wateringIntervalChanged,
                                modifier = Modifier.testTag(SettingsTestTags.WateringIntervalSave),
                            ) {
                                Text(strings.get(R.string.settings_save_watering_interval))
                            }
                            Button(
                                onClick = onRunScheduler,
                                enabled = state.connection.phase == BeetConnectionPhase.Connected,
                                modifier = Modifier.testTag(SettingsTestTags.WateringIntervalRunScheduler),
                            ) {
                                Text(strings.get(R.string.settings_run_scheduler))
                            }
                        }
                    }
                }
            }
            item {
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(SettingsTestTags.ValveCard),
                    colors = CardDefaults.elevatedCardColors(containerColor = Color(0xFFE8EFE8)),
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(strings.get(R.string.settings_valve_title), style = MaterialTheme.typography.titleMedium)
                        Text(strings.get(R.string.settings_valve_subtitle), style = MaterialTheme.typography.bodyMedium)
                        ValueGridRow(
                            strings.get(R.string.settings_label_valve_state),
                            deviceState?.let { valveStateLabel(it.valveState, strings) } ?: strings.get(R.string.placeholder_dash),
                            strings.get(R.string.settings_label_valve_enabled),
                            if (deviceState?.valveEnabled == true) strings.get(R.string.common_yes) else strings.get(R.string.common_no),
                            leftValueModifier = Modifier.testTag(SettingsTestTags.ValveStateValue),
                            rightValueModifier = Modifier.testTag(SettingsTestTags.ValveEnabledValue),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(
                                onClick = onOpenValve,
                                enabled = valveManualControlEnabled,
                                modifier = Modifier.testTag(SettingsTestTags.ValveOpenButton),
                            ) {
                                Text(strings.get(R.string.settings_open_valve))
                            }
                            Button(
                                onClick = onCloseValve,
                                enabled = valveManualControlEnabled,
                                modifier = Modifier.testTag(SettingsTestTags.ValveCloseButton),
                            ) {
                                Text(strings.get(R.string.settings_close_valve))
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(
                                onClick = onOpenValveCalibration,
                                enabled = state.connection.phase == BeetConnectionPhase.Connected,
                                modifier = Modifier.testTag(SettingsTestTags.ValveCalibrationButton),
                            ) {
                                Text(strings.get(R.string.settings_open_valve_calibration))
                            }
                        }
                    }
                }
            }
            item {
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(SettingsTestTags.ValveConfigCard),
                    colors = CardDefaults.elevatedCardColors(containerColor = Color(0xFFF6F2E9)),
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(strings.get(R.string.settings_valve_config_title), style = MaterialTheme.typography.titleMedium)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(strings.get(R.string.settings_label_valve_enabled))
                            Switch(
                                checked = valveEnabled,
                                onCheckedChange = { valveEnabled = it },
                                modifier = Modifier.testTag(SettingsTestTags.ValveConfigEnabledSwitch),
                            )
                        }
                        ValveNumberField(
                            value = moveDurationText,
                            label = strings.get(R.string.settings_label_valve_move_duration),
                            onValueChange = { moveDurationText = it; userEditedMoveDuration = true; android.util.Log.d("Settings", "moveDurationText changed to $it, userEdited=true") },
                            isError = moveDurationError != null,
                            supportingText = moveDurationError,
                            onInfoClick = { activeInfo = ValveSettingInfo.MoveDuration },
                            infoContentDescription = strings.get(R.string.settings_info_open_description),
                            modifier = Modifier.testTag(SettingsTestTags.ValveConfigMoveDurationField),
                        )
                        ValveNumberField(
                            value = settleDelayText,
                            label = strings.get(R.string.settings_label_valve_settle_delay),
                            onValueChange = { settleDelayText = it; userEditedSettleDelay = true; android.util.Log.d("Settings", "settleDelayText changed to $it, userEdited=true") },
                            isError = settleDelayError != null,
                            supportingText = settleDelayError,
                            onInfoClick = { activeInfo = ValveSettingInfo.SettleDelay },
                            infoContentDescription = strings.get(R.string.settings_info_open_description),
                            modifier = Modifier.testTag(SettingsTestTags.ValveConfigSettleDelayField),
                        )
                        ValveNumberField(
                            value = openHoldText,
                            label = strings.get(R.string.settings_label_valve_open_hold),
                            onValueChange = { openHoldText = it; userEditedOpenHold = true; android.util.Log.d("Settings", "openHoldText changed to $it, userEdited=true") },
                            isError = openHoldError != null,
                            supportingText = openHoldError,
                            onInfoClick = { activeInfo = ValveSettingInfo.OpenHold },
                            infoContentDescription = strings.get(R.string.settings_info_open_description),
                            modifier = Modifier.testTag(SettingsTestTags.ValveConfigOpenHoldField),
                        )
                        Button(
                            onClick = {
                                val configToSave = parseValveConfig(
                                    base = valveConfig,
                                    valveEnabled = valveEnabled,
                                    moveDurationText = moveDurationText,
                                    settleDelayText = settleDelayText,
                                    openHoldText = openHoldText,
                                )
                                configToSave?.let(onSaveValveConfig)
                            },
                            enabled = valveConfigChanged,
                            modifier = Modifier.testTag(SettingsTestTags.ValveConfigSave),
                        ) {
                            Text(strings.get(R.string.settings_save_valve))
                        }
                    }
                }
            }
            item {
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(SettingsTestTags.MaxActivePumpsCard),
                    colors = CardDefaults.elevatedCardColors(containerColor = Color(0xFFEFE6F2)),
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(strings.get(R.string.settings_max_active_pumps_title), style = MaterialTheme.typography.titleMedium)
                        Text(strings.get(R.string.settings_max_active_pumps_subtitle), style = MaterialTheme.typography.bodyMedium)
                        ValueGridRow(
                            strings.get(R.string.settings_label_max_active_pumps_current),
                            state.maxActivePumps?.let {
                                strings.get(R.string.settings_max_active_pumps_value, it)
                            } ?: strings.get(R.string.placeholder_dash),
                            strings.get(R.string.overview_label_active_pumps),
                            deviceState?.let { it.activePumps.toString() } ?: strings.get(R.string.placeholder_dash),
                            leftValueModifier = Modifier.testTag(SettingsTestTags.MaxActivePumpsCurrent),
                        )
                        val currentMax = maxPumpsDraft ?: 0
                        val liveMax = state.maxActivePumps
                        Row(
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            FilledIconButton(
                                onClick = { if (currentMax > 1) maxPumpsDraft = currentMax - 1 },
                                enabled = currentMax > 1,
                                modifier = Modifier.testTag(SettingsTestTags.MaxActivePumpsDecrement),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Remove,
                                    contentDescription = strings.get(R.string.settings_max_active_pumps_decrement),
                                )
                            }
                            Text(
                                text = currentMax.toString(),
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.testTag(SettingsTestTags.MaxActivePumpsDraftValue),
                            )
                            FilledIconButton(
                                onClick = { if (currentMax < 8) maxPumpsDraft = currentMax + 1 },
                                enabled = currentMax < 8,
                                modifier = Modifier.testTag(SettingsTestTags.MaxActivePumpsIncrement),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = strings.get(R.string.settings_max_active_pumps_increment),
                                )
                            }
                        }
                        Button(
                            onClick = { maxPumpsDraft?.let(onStoreMaxActivePumps) },
                            enabled = maxPumpsDraft != null && liveMax != null && maxPumpsDraft != liveMax,
                            modifier = Modifier.testTag(SettingsTestTags.MaxActivePumpsSave),
                        ) {
                            Text(strings.get(R.string.settings_max_active_pumps_save))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ValveNumberField(
    value: String,
    label: String,
    onValueChange: (String) -> Unit,
    isError: Boolean,
    supportingText: String?,
    onInfoClick: () -> Unit,
    infoContentDescription: String,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { updated -> onValueChange(updated.filter { it.isDigit() }) },
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        supportingText = supportingText?.let { text -> { Text(text) } },
        trailingIcon = {
            IconButton(onClick = onInfoClick) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = infoContentDescription,
                )
            }
        },
        modifier = modifier.fillMaxWidth(),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

private enum class ValveSettingInfo {
    MoveDuration,
    SettleDelay,
    OpenHold,
    ;

    fun title(strings: de.aarondietz.beetmeister.strings.BeetStringResolver): String = when (this) {
        MoveDuration -> strings.get(R.string.settings_valve_move_duration_info_title)
        SettleDelay -> strings.get(R.string.settings_valve_settle_delay_info_title)
        OpenHold -> strings.get(R.string.settings_valve_open_hold_info_title)
    }

    fun body(strings: de.aarondietz.beetmeister.strings.BeetStringResolver): String = when (this) {
        MoveDuration -> strings.get(R.string.settings_valve_move_duration_info_body)
        SettleDelay -> strings.get(R.string.settings_valve_settle_delay_info_body)
        OpenHold -> strings.get(R.string.settings_valve_open_hold_info_body)
    }
}

private fun moveDurationValidationMessage(
    value: String,
    strings: de.aarondietz.beetmeister.strings.BeetStringResolver,
): String? {
    val parsed = value.toIntOrNull() ?: return strings.get(R.string.settings_valve_move_duration_error)
    return if (parsed in 100..5000) null else strings.get(R.string.settings_valve_move_duration_error)
}

private fun settleDelayValidationMessage(
    value: String,
    strings: de.aarondietz.beetmeister.strings.BeetStringResolver,
): String? {
    val parsed = value.toIntOrNull() ?: return strings.get(R.string.settings_valve_settle_delay_error)
    return if (parsed in 0..5000) null else strings.get(R.string.settings_valve_settle_delay_error)
}

private fun openHoldValidationMessage(
    value: String,
    strings: de.aarondietz.beetmeister.strings.BeetStringResolver,
): String? {
    val parsed = value.toIntOrNull() ?: return strings.get(R.string.settings_valve_open_hold_error)
    return if (parsed in 0..10000) null else strings.get(R.string.settings_valve_open_hold_error)
}

private fun parseWateringIntervalSeconds(hoursText: String, minutesText: String): Int? {
    val hours = hoursText.toLongOrNull() ?: return null
    val minutes = minutesText.toLongOrNull() ?: return null
    if (minutes !in 0..59) {
        return null
    }
    val totalSeconds = (hours * 3600L) + (minutes * 60L)
    return if (totalSeconds in 0..Int.MAX_VALUE.toLong()) totalSeconds.toInt() else null
}

private fun wateringIntervalValidationMessage(
    hoursText: String,
    minutesText: String,
    strings: de.aarondietz.beetmeister.strings.BeetStringResolver,
): String? {
    val hours = hoursText.toLongOrNull() ?: return strings.get(R.string.settings_watering_interval_error)
    val minutes = minutesText.toLongOrNull() ?: return strings.get(R.string.settings_watering_interval_error)
    if (minutes !in 0..59) {
        return strings.get(R.string.settings_watering_interval_error)
    }
    val totalSeconds = (hours * 3600L) + (minutes * 60L)
    return if (totalSeconds in 300L..86400L) null else strings.get(R.string.settings_watering_interval_error)
}

private fun parseValveConfig(
    base: BeetValveConfig?,
    valveEnabled: Boolean,
    moveDurationText: String,
    settleDelayText: String,
    openHoldText: String,
): BeetValveConfig? {
    val current = base ?: return null
    val moveDuration = moveDurationText.toIntOrNull() ?: return null
    val settleDelay = settleDelayText.toIntOrNull() ?: return null
    val openHold = openHoldText.toIntOrNull() ?: return null
    if (moveDuration !in 100..5000 || settleDelay !in 0..5000 || openHold !in 0..10000) return null
    return current.copy(
        valveEnabled = valveEnabled,
        moveDurationMillis = moveDuration,
        settleDelayMillis = settleDelay,
        openHoldMillis = openHold,
    )
}
