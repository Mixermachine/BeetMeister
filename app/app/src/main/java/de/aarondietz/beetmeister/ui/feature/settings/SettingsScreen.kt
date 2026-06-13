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
import de.aarondietz.beetmeister.ui.core.formatting.valveStateLabel

@Composable
internal fun SettingsScreen(
    state: BeetRepositoryState,
    onRefreshValveConfig: () -> Unit,
    onSaveValveConfig: (BeetValveConfig) -> Unit,
    onOpenValveCalibration: () -> Unit,
    onOpenValve: () -> Unit,
    onCloseValve: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = rememberBeetStringResolver()
    val info = state.controllerInfo
    val valveConfig = state.valveConfig
    val deviceState = state.deviceState
    var valveEnabled by remember { mutableStateOf(false) }
    var moveDurationText by remember { mutableStateOf("") }
    var settleDelayText by remember { mutableStateOf("") }
    var openHoldText by remember { mutableStateOf("") }
    var activeInfo by remember { mutableStateOf<ValveSettingInfo?>(null) }

    LaunchedEffect(state.connection.phase) {
        if (state.connection.phase == BeetConnectionPhase.Connected) {
            onRefreshValveConfig()
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
    val valveMotionActive = deviceState?.valveState == "OPENING" || deviceState?.valveState == "CLOSING"
    val valveManualControlEnabled =
        state.connection.phase == BeetConnectionPhase.Connected &&
            valveConfig?.valveEnabled == true &&
            deviceState?.activePumps == 0 &&
            !valveMotionActive

    activeInfo?.let { info ->
        SettingInfoDialog(
            title = info.title(strings),
            body = info.body(strings),
            confirmLabel = strings.get(R.string.common_close),
            onDismiss = { activeInfo = null },
        )
    }

    BeetPullToRefreshBox(
        isRefreshing = state.valveConfigRefreshing,
        onRefresh = onRefreshValveConfig,
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
                            info?.deviceId ?: strings.get(R.string.placeholder_dash),
                            strings.get(R.string.settings_label_protocol),
                            info?.protocolVersion?.toString() ?: strings.get(R.string.placeholder_dash),
                        )
                        ValueGridRow(
                            strings.get(R.string.settings_label_firmware),
                            info?.firmwareVersion ?: strings.get(R.string.placeholder_dash),
                            strings.get(R.string.settings_label_pairs),
                            info?.pairCount?.toString() ?: strings.get(R.string.placeholder_dash),
                        )
                        ValueGridRow(
                            strings.get(R.string.settings_label_connection),
                            connectionPhaseLabel(state.connection.phase, strings),
                            strings.get(R.string.settings_label_address),
                            state.selectedAddress ?: strings.get(R.string.placeholder_dash),
                        )
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
            item {
                Button(onClick = onDisconnect) { Text(strings.get(R.string.common_disconnect)) }
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
