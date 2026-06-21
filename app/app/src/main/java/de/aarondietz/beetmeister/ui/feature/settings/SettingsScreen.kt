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
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
)

@Composable
internal fun SettingsScreen(
    state: BeetRepositoryState,
    onRefreshValveConfig: () -> Unit,
    onRefreshWateringInterval: () -> Unit,
    onSaveValveConfig: (BeetValveConfig) -> Unit,
    onSaveWateringInterval: (Int) -> Unit,
    onOpenValveCalibration: () -> Unit,
    onOpenValve: () -> Unit,
    onCloseValve: () -> Unit,
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
    var activeInfo by remember { mutableStateOf<ValveSettingInfo?>(null) }

    LaunchedEffect(state.connection.phase) {
        if (state.connection.phase == BeetConnectionPhase.Connected) {
            onRefreshValveConfig()
            onRefreshWateringInterval()
        }
    }

    LaunchedEffect(valveConfig) {
        if (valveConfig != null) {
            valveEnabled = valveConfig.valveEnabled
            moveDurationText = valveConfig.moveDurationMillis.toString()
            settleDelayText = valveConfig.settleDelayMillis.toString()
            openHoldText = valveConfig.openHoldMillis.toString()
        }
    }

    LaunchedEffect(state.wateringInterval) {
        val interval = state.wateringInterval ?: return@LaunchedEffect
        intervalHoursText = (interval.seconds / 3600).toString()
        intervalMinutesText = ((interval.seconds % 3600) / 60).toString()
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
    val valveMotionActive = deviceState?.valveState == "OPENING" || deviceState?.valveState == "CLOSING"
    val valveManualControlEnabled =
        state.connection.phase == BeetConnectionPhase.Connected &&
            valveConfig?.valveEnabled == true &&
            deviceState?.activePumps == 0 &&
            !valveMotionActive
    val hasUnsavedChanges = valveConfigDirty || wateringIntervalDirty
    val savableDraft = if (!hasUnsavedChanges) {
        null
    } else if ((valveConfigDirty && editedValveConfig == null) || (wateringIntervalDirty && wateringIntervalSeconds == null)) {
        null
    } else {
        SettingsSaveDraft(
            valveConfig = if (valveConfigDirty) editedValveConfig else null,
            wateringIntervalSeconds = if (wateringIntervalDirty) wateringIntervalSeconds else null,
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
        modifier = modifier,
    ) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
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
                        )
                        ValueGridRow(
                            strings.get(R.string.settings_label_firmware),
                            info?.firmwareVersion ?: maintenanceInfo?.firmwareVersion ?: strings.get(R.string.placeholder_dash),
                            strings.get(R.string.settings_label_pairs),
                            info?.pairCount?.toString() ?: strings.get(R.string.placeholder_dash),
                        )
                        if (maintenanceInfo != null) {
                            ValueGridRow(
                                strings.get(R.string.settings_label_build),
                                maintenanceInfo.buildLabel,
                                strings.get(R.string.settings_label_firmware_source),
                                maintenanceImageKindLabel(maintenanceInfo.imageKind, strings),
                            )
                        }
                        ValueGridRow(
                            strings.get(R.string.settings_label_connection),
                            connectionPhaseLabel(state.connection.phase, strings),
                            strings.get(R.string.settings_label_address),
                            state.selectedAddress ?: strings.get(R.string.placeholder_dash),
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(onClick = onDisconnect) {
                            Text(strings.get(R.string.common_disconnect))
                        }
                    }
                }
            }
            item {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.elevatedCardColors(containerColor = Color(0xFFF6F0E5)),
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(strings.get(R.string.settings_firmware_update_title), style = MaterialTheme.typography.titleMedium)
                        Text(strings.get(R.string.settings_firmware_update_subtitle), style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = onOpenFirmwareUpdate) {
                            Text(strings.get(R.string.settings_open_firmware_update))
                        }
                    }
                }
            }
            item {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
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
                        )
                        OutlinedTextField(
                            value = intervalHoursText,
                            onValueChange = { intervalHoursText = it.filter(Char::isDigit) },
                            label = { Text(strings.get(R.string.settings_label_interval_hours)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                        OutlinedTextField(
                            value = intervalMinutesText,
                            onValueChange = { intervalMinutesText = it.filter(Char::isDigit) },
                            label = { Text(strings.get(R.string.settings_label_interval_minutes)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                        if (showWateringIntervalError) {
                            Text(
                                text = wateringIntervalError ?: "",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Button(
                            onClick = { wateringIntervalSeconds?.let(onSaveWateringInterval) },
                            enabled = wateringIntervalChanged,
                        ) {
                            Text(strings.get(R.string.settings_save_watering_interval))
                        }
                    }
                }
            }
            item {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
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
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = onOpenValve, enabled = valveManualControlEnabled) {
                                Text(strings.get(R.string.settings_open_valve))
                            }
                            Button(onClick = onCloseValve, enabled = valveManualControlEnabled) {
                                Text(strings.get(R.string.settings_close_valve))
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(
                                onClick = onOpenValveCalibration,
                                enabled = state.connection.phase == BeetConnectionPhase.Connected,
                            ) {
                                Text(strings.get(R.string.settings_open_valve_calibration))
                            }
                        }
                    }
                }
            }
            item {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.elevatedCardColors(containerColor = Color(0xFFF6F2E9)),
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(strings.get(R.string.settings_valve_config_title), style = MaterialTheme.typography.titleMedium)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(strings.get(R.string.settings_label_valve_enabled))
                            Switch(checked = valveEnabled, onCheckedChange = { valveEnabled = it })
                        }
                        ValveNumberField(
                            value = moveDurationText,
                            label = strings.get(R.string.settings_label_valve_move_duration),
                            onValueChange = { moveDurationText = it },
                            isError = moveDurationError != null,
                            supportingText = moveDurationError,
                            onInfoClick = { activeInfo = ValveSettingInfo.MoveDuration },
                            infoContentDescription = strings.get(R.string.settings_info_open_description),
                        )
                        ValveNumberField(
                            value = settleDelayText,
                            label = strings.get(R.string.settings_label_valve_settle_delay),
                            onValueChange = { settleDelayText = it },
                            isError = settleDelayError != null,
                            supportingText = settleDelayError,
                            onInfoClick = { activeInfo = ValveSettingInfo.SettleDelay },
                            infoContentDescription = strings.get(R.string.settings_info_open_description),
                        )
                        ValveNumberField(
                            value = openHoldText,
                            label = strings.get(R.string.settings_label_valve_open_hold),
                            onValueChange = { openHoldText = it },
                            isError = openHoldError != null,
                            supportingText = openHoldError,
                            onInfoClick = { activeInfo = ValveSettingInfo.OpenHold },
                            infoContentDescription = strings.get(R.string.settings_info_open_description),
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
                        ) {
                            Text(strings.get(R.string.settings_save_valve))
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
        modifier = Modifier.fillMaxWidth(),
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
