package de.aarondietz.beetmeister.ui.feature.overview

import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.AssistChipDefaults
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
import androidx.compose.ui.platform.testTag
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
import de.aarondietz.beetmeister.ui.core.theme.StatusErrorContainer
import de.aarondietz.beetmeister.ui.core.theme.StatusErrorOnContainer
import de.aarondietz.beetmeister.ui.core.theme.StatusOkContainer
import de.aarondietz.beetmeister.ui.core.theme.StatusOkOnContainer
import de.aarondietz.beetmeister.ui.core.theme.StatusWarningContainer
import de.aarondietz.beetmeister.ui.core.theme.StatusWarningOnContainer
import de.aarondietz.beetmeister.ui.core.theme.StatusWaterContainer
import de.aarondietz.beetmeister.ui.core.theme.StatusWaterOnContainer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import de.aarondietz.beetmeister.ui.core.preview.PreviewData
import de.aarondietz.beetmeister.ui.core.theme.BeetMeisterTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.tooling.preview.Preview

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
        modifier = modifier.testTag(OverviewTestTags.List),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            SystemValuesCard(state = state)
        }
        items(state.pairStates, key = { pair -> pair.pairIndex }) { pair ->
            PairOverviewCard(
                pair = pair,
                pairName = state.pairNames[pair.pairIndex],
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
    val device = state.deviceState
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMillis = System.currentTimeMillis()
            delay(60_000)
        }
    }
    val runningSinceUnixSeconds = device?.let {
        runningSinceUnixSeconds(
            connectedAtMillis = state.connectedAtMillis,
            connectedAtControllerUptimeSeconds = state.connectedAtControllerUptimeSeconds,
            fallbackUptimeSeconds = it.uptimeSeconds,
            nowMillis = nowMillis,
        )
    }
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(OverviewTestTags.SystemValuesCard),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                strings.get(R.string.overview_title_system_values),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(12.dp))
            ValueGridRow(
                strings.get(R.string.overview_label_battery),
                device?.let { formatMillivolts(it.batteryMillivolts, strings) },
                strings.get(R.string.overview_label_approx),
                device?.let { formatPercent(it.batteryPercentApprox, strings) },
            )
            ValueGridRow(
                strings.get(R.string.overview_label_battery_state),
                device?.let { batteryStateLabel(it.batteryState, strings) },
                strings.get(R.string.overview_label_next_check),
                device?.let { formatDuration(it.nextCheckInSeconds, strings) },
            )
            ValueGridRow(
                strings.get(R.string.overview_label_active_pumps),
                device?.let { it.activePumps.toString() },
                strings.get(R.string.overview_label_valve),
                device?.let { valveStateLabel(it.valveState, strings) },
            )
            ValueGridRow(
                strings.get(R.string.overview_label_wifi),
                device?.let { yesNo(it.wifiConnected, strings) },
                strings.get(R.string.overview_label_time_valid),
                device?.let { yesNo(it.timeValid, strings) },
            )
            ValueGridRow(
                strings.get(R.string.overview_label_running_since),
                runningSinceUnixSeconds?.let { formatUnixSeconds(it, strings) },
                strings.get(R.string.overview_label_mqtt),
                device?.let { yesNo(it.mqttConnected, strings) },
            )
            ValueGridRow(
                strings.get(R.string.overview_label_uptime),
                device?.let { formatDuration(it.uptimeSeconds.toInt(), strings) },
                strings.get(R.string.settings_label_valve_enabled),
                device?.let { yesNo(it.valveEnabled, strings) },
            )
        }
    }
}

@Composable
private fun PairOverviewCard(
    pair: BeetPairState,
    pairName: String?,
    onDetails: () -> Unit,
    onClearError: () -> Unit,
    onToggleEnabled: () -> Unit,
    strings: de.aarondietz.beetmeister.strings.BeetStringResolver,
) {
    val (chipContainerColor, chipContentColor) = when {
        !pair.enabled -> MaterialTheme.colorScheme.surfaceContainerHighest to MaterialTheme.colorScheme.onSurfaceVariant
        pair.state == "FAULT" -> StatusErrorContainer to StatusErrorOnContainer
        pair.blocked -> StatusWarningContainer to StatusWarningOnContainer
        pair.state == "WATERING" -> StatusWaterContainer to StatusWaterOnContainer
        else -> StatusOkContainer to StatusOkOnContainer
    }

    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (pair.enabled) 1f else 0.75f)
            .testTag(OverviewTestTags.PairCard),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (pairName != null && pairName.isNotBlank()) pairName
                    else strings.get(R.string.common_pair_number, pair.pairIndex),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.testTag(OverviewTestTags.PairName),
                )
                AssistChip(
                    onClick = {},
                    label = { Text(pairStateLabel(pair.state, strings)) },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = chipContainerColor,
                        labelColor = chipContentColor,
                    ),
                    border = null,
                    modifier = Modifier.testTag(OverviewTestTags.PairState),
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            ValueGridRow(
                strings.get(R.string.overview_label_moisture),
                formatPercent(pair.moisturePercent, strings),
                strings.get(R.string.overview_label_sensor),
                formatMillivolts(pair.sensorMillivolts, strings),
                leftValueModifier = Modifier.testTag(OverviewTestTags.PairMoisture),
                rightValueModifier = Modifier.testTag(OverviewTestTags.PairSensor),
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                )
            } else if (pair.blocked || pair.state == "FAULT") {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = strings.get(R.string.common_reason_value, blockReasonCodeLabel(pair.blockReason, strings)),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onDetails,
                    modifier = Modifier.testTag(OverviewTestTags.PairDetailsButton),
                ) { Text(strings.get(R.string.common_details)) }
                PairErrorClearButton(
                    canClearError = pair.sensorValid && (pair.blocked || pair.state == "FAULT"),
                    onClear = onClearError,
                    modifier = Modifier.testTag(OverviewTestTags.PairClearErrorButton),
                )
                PairEnabledToggleButton(
                    pairEnabled = pair.enabled,
                    onToggle = onToggleEnabled,
                    modifier = Modifier.testTag(OverviewTestTags.PairEnableToggle),
                )
            }
        }
    }
}

// region Previews

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true, backgroundColor = 0xFFF6F1E4)
@Composable
private fun OverviewScreenPreview_AllLoaded() {
    BeetMeisterTheme {
        OverviewScreen(
            state = PreviewData.connectedState(),
            onPairSelected = {},
            onClearError = {},
            onToggleEnabled = {},
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true, backgroundColor = 0xFFF6F1E4)
@Composable
private fun OverviewScreenPreview_DeviceNull() {
    val state = PreviewData.connectedState().copy(deviceState = null)
    BeetMeisterTheme {
        OverviewScreen(
            state = state,
            onPairSelected = {},
            onClearError = {},
            onToggleEnabled = {},
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true, backgroundColor = 0xFFF6F1E4)
@Composable
private fun OverviewScreenPreview_FewPairs() {
    val state: BeetRepositoryState = PreviewData.connectedState().copy(
        pairStates = PreviewData.fewPairStates(),
        pairNames = mapOf(1 to "Front Garden", 2 to "Greenhouse A"),
    )
    BeetMeisterTheme {
        OverviewScreen(
            state = state,
            onPairSelected = {},
            onClearError = {},
            onToggleEnabled = {},
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true, backgroundColor = 0xFFF6F1E4)
@Composable
private fun OverviewScreenPreview_AllIdle() {
    val state: BeetRepositoryState = PreviewData.connectedState().copy(
        pairStates = PreviewData.allIdlePairStates(),
        pairNames = emptyMap(),
    )
    BeetMeisterTheme {
        OverviewScreen(
            state = state,
            onPairSelected = {},
            onClearError = {},
            onToggleEnabled = {},
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true, backgroundColor = 0xFFF6F1E4)
@Composable
private fun OverviewScreenPreview_AllFaulted() {
    val state: BeetRepositoryState = PreviewData.connectedState().copy(
        pairStates = PreviewData.allFaultedPairStates(),
    )
    BeetMeisterTheme {
        OverviewScreen(
            state = state,
            onPairSelected = {},
            onClearError = {},
            onToggleEnabled = {},
        )
    }
}

// endregion
