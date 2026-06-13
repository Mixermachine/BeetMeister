package de.aarondietz.beetmeister.ui.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
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
import de.aarondietz.beetmeister.ui.core.component.ValueGridRow
import de.aarondietz.beetmeister.ui.core.formatting.formatDuration
import de.aarondietz.beetmeister.ui.core.formatting.valveStateLabel

@Composable
internal fun ValveCalibrationScreen(
    state: BeetRepositoryState,
    onBack: () -> Unit,
    onRefreshValveConfig: () -> Unit,
    onPreviewValvePosition: (Int) -> Unit,
    onSaveValveConfig: (BeetValveConfig) -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = rememberBeetStringResolver()
    val valveConfig = state.valveConfig
    val deviceState = state.deviceState
    var minPulseText by rememberSaveable { mutableStateOf("") }
    var maxPulseText by rememberSaveable { mutableStateOf("") }
    var openPulseText by rememberSaveable { mutableStateOf("") }
    var shutPulseText by rememberSaveable { mutableStateOf("") }
    var previewPulseMicros by rememberSaveable { mutableIntStateOf(ValvePulseMinimumMicros) }

    LaunchedEffect(state.connection.phase) {
        if (state.connection.phase == BeetConnectionPhase.Connected) {
            onRefreshValveConfig()
        }
    }

    LaunchedEffect(valveConfig, deviceState?.valveState) {
        if (valveConfig != null) {
            minPulseText = valveConfig.servoMinPulseMicros.toString()
            maxPulseText = valveConfig.servoMaxPulseMicros.toString()
            openPulseText = valveConfig.openPulseMicros.toString()
            shutPulseText = valveConfig.shutPulseMicros.toString()
            previewPulseMicros = if (deviceState?.valveState == "OPEN") {
                valveConfig.openPulseMicros
            } else {
                valveConfig.shutPulseMicros
            }
        }
    }

    val editedValveConfig = parseCalibrationValveConfig(
        base = valveConfig,
        minPulseText = minPulseText,
        maxPulseText = maxPulseText,
        openPulseText = openPulseText,
        shutPulseText = shutPulseText,
    )
    val previewPulse = editedValveConfig?.let {
        clampValvePulse(previewPulseMicros, it.servoMinPulseMicros, it.servoMaxPulseMicros)
    } ?: previewPulseMicros
    val previewPercent = editedValveConfig?.let {
        valvePulseToPercent(previewPulse, it.servoMinPulseMicros, it.servoMaxPulseMicros)
    } ?: 0f
    val calibrationChanged = editedValveConfig != null && editedValveConfig != valveConfig
    val previewEnabled =
        state.connection.phase == BeetConnectionPhase.Connected &&
            editedValveConfig != null &&
            deviceState?.activePumps == 0 &&
            deviceState?.valveState != "OPENING" &&
            deviceState?.valveState != "CLOSING"

    val openPercent = editedValveConfig?.let {
        valvePulseToPercent(it.openPulseMicros, it.servoMinPulseMicros, it.servoMaxPulseMicros)
    }
    val shutPercent = editedValveConfig?.let {
        valvePulseToPercent(it.shutPulseMicros, it.servoMinPulseMicros, it.servoMaxPulseMicros)
    }

    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(strings.get(R.string.valve_calibration_title), style = MaterialTheme.typography.headlineSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onBack) { Text(strings.get(R.string.common_back)) }
                    TextButton(onClick = onRefreshValveConfig) { Text(strings.get(R.string.valve_calibration_reload_values)) }
                }
            }
        }
        item {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(containerColor = Color(0xFFE9F1F2)),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(strings.get(R.string.valve_calibration_subtitle), style = MaterialTheme.typography.bodyMedium)
                    ValueGridRow(
                        strings.get(R.string.valve_calibration_label_preview_percent),
                        strings.get(R.string.common_percent, previewPercent.toInt()),
                        strings.get(R.string.valve_calibration_label_preview_pulse),
                        strings.get(R.string.common_microseconds, previewPulse),
                    )
                    ValueGridRow(
                        strings.get(R.string.valve_calibration_label_open_marker),
                        markerLabel(openPercent, editedValveConfig?.openPulseMicros, strings),
                        strings.get(R.string.valve_calibration_label_shut_marker),
                        markerLabel(shutPercent, editedValveConfig?.shutPulseMicros, strings),
                    )
                    if (valveConfig != null) {
                        ValueGridRow(
                            strings.get(R.string.settings_label_valve_state),
                            state.deviceState?.let { valveStateLabel(it.valveState, strings) } ?: strings.get(R.string.placeholder_dash),
                            strings.get(R.string.settings_label_valve_move_duration),
                            formatDuration((valveConfig.moveDurationMillis / 1000).coerceAtLeast(0), strings),
                        )
                    }
                    Slider(
                        value = previewPercent,
                        onValueChange = { percent ->
                            editedValveConfig?.let {
                                previewPulseMicros = valvePercentToPulse(percent, it.servoMinPulseMicros, it.servoMaxPulseMicros)
                            }
                        },
                        valueRange = 0f..100f,
                        enabled = previewEnabled,
                        onValueChangeFinished = {
                            editedValveConfig?.let {
                                onPreviewValvePosition(
                                    clampValvePulse(previewPulseMicros, it.servoMinPulseMicros, it.servoMaxPulseMicros),
                                )
                            }
                        },
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        StepButton(label = "-5%", enabled = previewEnabled) {
                            editedValveConfig?.let {
                                previewPulseMicros = valvePercentToPulse(
                                    (previewPercent - 5f).coerceAtLeast(0f),
                                    it.servoMinPulseMicros,
                                    it.servoMaxPulseMicros,
                                )
                                onPreviewValvePosition(previewPulseMicros)
                            }
                        }
                        StepButton(label = "-1%", enabled = previewEnabled) {
                            editedValveConfig?.let {
                                previewPulseMicros = valvePercentToPulse(
                                    (previewPercent - 1f).coerceAtLeast(0f),
                                    it.servoMinPulseMicros,
                                    it.servoMaxPulseMicros,
                                )
                                onPreviewValvePosition(previewPulseMicros)
                            }
                        }
                        StepButton(label = "+1%", enabled = previewEnabled) {
                            editedValveConfig?.let {
                                previewPulseMicros = valvePercentToPulse(
                                    (previewPercent + 1f).coerceAtMost(100f),
                                    it.servoMinPulseMicros,
                                    it.servoMaxPulseMicros,
                                )
                                onPreviewValvePosition(previewPulseMicros)
                            }
                        }
                        StepButton(label = "+5%", enabled = previewEnabled) {
                            editedValveConfig?.let {
                                previewPulseMicros = valvePercentToPulse(
                                    (previewPercent + 5f).coerceAtMost(100f),
                                    it.servoMinPulseMicros,
                                    it.servoMaxPulseMicros,
                                )
                                onPreviewValvePosition(previewPulseMicros)
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Button(
                            onClick = { openPulseText = previewPulse.toString() },
                            enabled = editedValveConfig != null,
                        ) {
                            Text(strings.get(R.string.valve_calibration_set_open))
                        }
                        Button(
                            onClick = { shutPulseText = previewPulse.toString() },
                            enabled = editedValveConfig != null,
                        ) {
                            Text(strings.get(R.string.valve_calibration_set_shut))
                        }
                    }
                }
            }
        }
        item {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(containerColor = Color(0xFFF4F0E7)),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(strings.get(R.string.valve_calibration_range_title), style = MaterialTheme.typography.titleMedium)
                    PulseField(
                        value = minPulseText,
                        label = strings.get(R.string.valve_calibration_label_min_pulse),
                        onValueChange = { minPulseText = it },
                    )
                    PulseField(
                        value = maxPulseText,
                        label = strings.get(R.string.valve_calibration_label_max_pulse),
                        onValueChange = { maxPulseText = it },
                    )
                    PulseField(
                        value = openPulseText,
                        label = strings.get(R.string.valve_calibration_label_open_pulse),
                        onValueChange = { openPulseText = it },
                    )
                    PulseField(
                        value = shutPulseText,
                        label = strings.get(R.string.valve_calibration_label_shut_pulse),
                        onValueChange = { shutPulseText = it },
                    )
                    Button(
                        onClick = { editedValveConfig?.let(onSaveValveConfig) },
                        enabled = calibrationChanged,
                    ) {
                        Text(strings.get(R.string.valve_calibration_save))
                    }
                }
            }
        }
    }
}

@Composable
private fun PulseField(
    value: String,
    label: String,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { updated -> onValueChange(updated.filter(Char::isDigit)) },
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

@Composable
private fun RowScope.StepButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.weight(1f),
    ) {
        Text(label)
    }
}

private fun markerLabel(percent: Float?, pulseMicros: Int?, strings: de.aarondietz.beetmeister.strings.BeetStringResolver): String {
    if (percent == null || pulseMicros == null) {
        return strings.get(R.string.placeholder_dash)
    }
    return strings.get(R.string.valve_calibration_marker_value, percent.toInt(), pulseMicros)
}

private fun parseCalibrationValveConfig(
    base: BeetValveConfig?,
    minPulseText: String,
    maxPulseText: String,
    openPulseText: String,
    shutPulseText: String,
): BeetValveConfig? {
    val current = base ?: return null
    val minPulse = minPulseText.toIntOrNull() ?: return null
    val maxPulse = maxPulseText.toIntOrNull() ?: return null
    val openPulse = openPulseText.toIntOrNull() ?: return null
    val shutPulse = shutPulseText.toIntOrNull() ?: return null
    if (!isValidValvePulseRange(minPulse, maxPulse)) return null
    if (openPulse !in minPulse..maxPulse || shutPulse !in minPulse..maxPulse) return null
    return current.copy(
        servoMinPulseMicros = minPulse,
        servoMaxPulseMicros = maxPulse,
        openPulseMicros = openPulse,
        shutPulseMicros = shutPulse,
    )
}
