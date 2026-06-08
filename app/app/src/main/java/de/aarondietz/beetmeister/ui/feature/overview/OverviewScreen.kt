package de.aarondietz.beetmeister.ui.feature.overview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import de.aarondietz.beetmeister.model.controller.BeetPairState
import de.aarondietz.beetmeister.model.repository.BeetRepositoryState
import de.aarondietz.beetmeister.R
import de.aarondietz.beetmeister.strings.rememberBeetStringResolver
import de.aarondietz.beetmeister.ui.core.component.PairErrorClearButton
import de.aarondietz.beetmeister.ui.core.component.PairEnabledToggleButton
import de.aarondietz.beetmeister.ui.core.component.ValueGridRow
import de.aarondietz.beetmeister.ui.core.formatting.batteryStateLabel
import de.aarondietz.beetmeister.ui.core.formatting.blockReasonCodeLabel
import de.aarondietz.beetmeister.ui.core.formatting.formatDuration
import de.aarondietz.beetmeister.ui.core.formatting.formatMillivolts
import de.aarondietz.beetmeister.ui.core.formatting.formatPercent
import de.aarondietz.beetmeister.ui.core.formatting.formatUnixSeconds
import de.aarondietz.beetmeister.ui.core.formatting.pairStateLabel
import de.aarondietz.beetmeister.ui.core.formatting.runSourceLabel
import de.aarondietz.beetmeister.ui.core.formatting.valveStateLabel
import de.aarondietz.beetmeister.ui.core.formatting.yesNo
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable
internal fun OverviewScreen(
    state: BeetRepositoryState,
    onPairSelected: (Int) -> Unit,
    onClearError: (Int) -> Unit,
    onToggleEnabled: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = rememberBeetStringResolver()
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SystemValuesCard(state = state)
        }
        items(state.pairStates, key = { pair -> pair.pairIndex }) { pair ->
            PairOverviewCard(
                pair = pair,
                onDetails = { onPairSelected(pair.pairIndex) },
                onClearError = { onClearError(pair.pairIndex) },
                onToggleEnabled = { onToggleEnabled(pair.pairIndex) },
                strings = strings,
            )
        }
    }
}

@Composable
private fun SystemValuesCard(state: BeetRepositoryState) {
    val strings = rememberBeetStringResolver()
    val device = state.deviceState ?: return
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMillis = System.currentTimeMillis()
            delay(60_000)
        }
    }
    val runningSinceUnixSeconds = runningSinceUnixSeconds(
        connectedAtMillis = state.connectedAtMillis,
        connectedAtControllerUptimeSeconds = state.connectedAtControllerUptimeSeconds,
        fallbackUptimeSeconds = device.uptimeSeconds,
        nowMillis = nowMillis,
    )
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = Color(0xFFF6F1E4)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(strings.get(R.string.overview_title_system_values), style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(12.dp))
            ValueGridRow(
                strings.get(R.string.overview_label_battery),
                formatMillivolts(device.batteryMillivolts, strings),
                strings.get(R.string.overview_label_approx),
                formatPercent(device.batteryPercentApprox, strings),
            )
            ValueGridRow(
                strings.get(R.string.overview_label_battery_state),
                batteryStateLabel(device.batteryState, strings),
                strings.get(R.string.overview_label_next_check),
                formatDuration(device.nextCheckInSeconds, strings),
            )
            ValueGridRow(
                strings.get(R.string.overview_label_active_pumps),
                device.activePumps.toString(),
                strings.get(R.string.overview_label_valve),
                valveStateLabel(device.valveState, strings),
            )
            ValueGridRow(
                strings.get(R.string.overview_label_wifi),
                yesNo(device.wifiConnected, strings),
                strings.get(R.string.overview_label_time_valid),
                yesNo(device.timeValid, strings),
            )
            ValueGridRow(
                strings.get(R.string.overview_label_running_since),
                formatUnixSeconds(runningSinceUnixSeconds, strings),
                strings.get(R.string.overview_label_mqtt),
                yesNo(device.mqttConnected, strings),
            )
            ValueGridRow(
                strings.get(R.string.overview_label_uptime),
                formatDuration(device.uptimeSeconds.toInt(), strings),
                strings.get(R.string.settings_label_valve_enabled),
                yesNo(device.valveEnabled, strings),
            )
        }
    }
}

@Composable
private fun PairOverviewCard(
    pair: BeetPairState,
    onDetails: () -> Unit,
    onClearError: () -> Unit,
    onToggleEnabled: () -> Unit,
    strings: de.aarondietz.beetmeister.strings.BeetStringResolver,
) {
    val tone = when {
        !pair.enabled -> Color(0xFFE3E0DA)
        pair.state == "FAULT" -> Color(0xFFF3D7D3)
        pair.blocked -> Color(0xFFF0E1BF)
        pair.state == "WATERING" -> Color(0xFFD5E7F3)
        else -> Color(0xFFF9F8F2)
    }
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = tone),
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (pair.enabled) 1f else 0.7f),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(strings.get(R.string.common_pair_number, pair.pairIndex), style = MaterialTheme.typography.titleLarge)
                AssistChip(onClick = {}, label = { Text(pairStateLabel(pair.state, strings)) })
            }
            Spacer(modifier = Modifier.height(10.dp))
            ValueGridRow(
                strings.get(R.string.overview_label_moisture),
                formatPercent(pair.moisturePercent, strings),
                strings.get(R.string.overview_label_sensor),
                formatMillivolts(pair.sensorMillivolts, strings),
            )
            ValueGridRow(
                strings.get(R.string.overview_label_source),
                runSourceLabel(pair.source, strings),
                strings.get(R.string.overview_label_remaining),
                formatDuration(pair.remainingSeconds, strings),
            )
            if (!pair.enabled) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = strings.get(R.string.overview_pair_disabled_info),
                    color = Color(0xFF545454),
                    fontWeight = FontWeight.SemiBold,
                )
            } else if (pair.blocked || pair.state == "FAULT") {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = strings.get(R.string.common_reason_value, blockReasonCodeLabel(pair.blockReason, strings)),
                    color = Color(0xFF7D4632),
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onDetails) { Text(strings.get(R.string.common_details)) }
                PairErrorClearButton(
                    canClearError = pair.sensorValid && (pair.blocked || pair.state == "FAULT"),
                    onClear = onClearError,
                )
                PairEnabledToggleButton(pairEnabled = pair.enabled, onToggle = onToggleEnabled)
            }
        }
    }
}
