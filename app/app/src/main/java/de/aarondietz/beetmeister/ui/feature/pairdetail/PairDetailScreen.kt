package de.aarondietz.beetmeister.ui.feature.pairdetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import de.aarondietz.beetmeister.R
import de.aarondietz.beetmeister.model.controller.BeetPairState
import de.aarondietz.beetmeister.strings.rememberBeetStringResolver
import de.aarondietz.beetmeister.ui.core.component.PairErrorClearButton
import de.aarondietz.beetmeister.ui.core.component.PairEnabledToggleButton
import de.aarondietz.beetmeister.ui.core.component.ValueGridRow
import de.aarondietz.beetmeister.ui.core.formatting.blockReasonCodeLabel
import de.aarondietz.beetmeister.ui.core.formatting.formatDuration
import de.aarondietz.beetmeister.ui.core.formatting.formatMillivolts
import de.aarondietz.beetmeister.ui.core.formatting.formatPercent
import de.aarondietz.beetmeister.ui.core.formatting.pairStateLabel
import de.aarondietz.beetmeister.ui.core.formatting.runSourceLabel
import de.aarondietz.beetmeister.ui.core.formatting.yesNo

@Composable
internal fun PairDetailScreen(
    pairState: BeetPairState,
    onBack: () -> Unit,
    onToggleEnabled: (Int) -> Unit,
    onManualStart: (Int, Int?) -> Unit,
    onManualStop: (Int) -> Unit,
    onMoistureTestStart: (Int) -> Unit,
    onClearError: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = rememberBeetStringResolver()
    var durationText by rememberSaveable(pairState.pairIndex) { mutableStateOf("") }
    val canStartMoistureTest = pairState.enabled &&
        pairState.sensorValid &&
        !pairState.blocked &&
        pairState.state !in setOf("FAULT", "WATERING", "SANITY_CHECK", "MOISTURE_TEST", "WAITING_FOR_SLOT")

    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(containerColor = Color(0xFFF9F6EF)),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(strings.get(R.string.common_pair_number, pairState.pairIndex), style = MaterialTheme.typography.headlineSmall)
                    Spacer(modifier = Modifier.height(12.dp))
                    ValueGridRow(
                        strings.get(R.string.pair_detail_label_state),
                        pairStateLabel(pairState.state, strings),
                        strings.get(R.string.pair_detail_label_source),
                        runSourceLabel(pairState.source, strings),
                    )
                    ValueGridRow(
                        strings.get(R.string.pair_detail_label_moisture),
                        formatPercent(pairState.moisturePercent, strings),
                        strings.get(R.string.pair_detail_label_sensor),
                        formatMillivolts(pairState.sensorMillivolts, strings),
                    )
                    ValueGridRow(
                        strings.get(R.string.pair_detail_label_enabled),
                        yesNo(pairState.enabled, strings),
                        strings.get(R.string.pair_detail_label_sensor_valid),
                        yesNo(pairState.sensorValid, strings),
                    )
                    ValueGridRow(
                        strings.get(R.string.pair_detail_label_blocked),
                        yesNo(pairState.blocked, strings),
                        strings.get(R.string.pair_detail_label_remaining),
                        formatDuration(pairState.remainingSeconds, strings),
                    )
                    if (!pairState.enabled) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(strings.get(R.string.pair_detail_disabled_info), color = Color(0xFF545454))
                    } else if (pairState.blocked || pairState.state == "FAULT") {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            strings.get(R.string.common_reason_value, blockReasonCodeLabel(pairState.blockReason, strings)),
                            color = Color(0xFF7D4632),
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    PairEnabledToggleButton(
                        pairEnabled = pairState.enabled,
                        onToggle = { onToggleEnabled(pairState.pairIndex) },
                    )
                }
            }
        }
        item {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(containerColor = Color(0xFFEAF0E2)),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(strings.get(R.string.pair_detail_manual_watering), style = MaterialTheme.typography.titleLarge)
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = durationText,
                        onValueChange = { input -> durationText = input.filter(Char::isDigit) },
                        label = { Text(strings.get(R.string.pair_detail_timed_start_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = pairState.enabled,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        FilledTonalButton(
                            onClick = { onManualStart(pairState.pairIndex, null) },
                            enabled = pairState.enabled,
                        ) {
                            Text(strings.get(R.string.pair_detail_start_default))
                        }
                        Button(
                            onClick = { onManualStart(pairState.pairIndex, durationText.toIntOrNull()) },
                            enabled = pairState.enabled,
                        ) {
                            Text(strings.get(R.string.pair_detail_start_timed))
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(
                            onClick = { onManualStop(pairState.pairIndex) },
                            enabled = pairState.enabled,
                        ) {
                            Text(strings.get(R.string.pair_detail_stop))
                        }
                        PairErrorClearButton(
                            canClearError = pairState.sensorValid && (pairState.blocked || pairState.state == "FAULT"),
                            onClear = { onClearError(pairState.pairIndex) },
                        )
                    }
                }
            }
        }
        item {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(containerColor = Color(0xFFF0E7DA)),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(strings.get(R.string.pair_detail_irrigation_detection), style = MaterialTheme.typography.titleLarge)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        strings.get(R.string.pair_detail_detection_description),
                        color = Color(0xFF545454),
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = { onMoistureTestStart(pairState.pairIndex) },
                        enabled = canStartMoistureTest,
                    ) {
                        Text(
                            strings.get(
                                if (pairState.state == "MOISTURE_TEST") {
                                    R.string.pair_detail_testing
                                } else {
                                    R.string.pair_detail_test_detection
                                },
                            ),
                        )
                    }
                }
            }
        }
    }
}
