package de.aarondietz.beetmeister.ui.feature.calibration

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
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import de.aarondietz.beetmeister.R
import de.aarondietz.beetmeister.model.connection.BeetConnectionPhase
import de.aarondietz.beetmeister.model.controller.BeetPairState
import de.aarondietz.beetmeister.model.repository.BeetRepositoryState
import de.aarondietz.beetmeister.strings.BeetStringResolver
import de.aarondietz.beetmeister.strings.rememberBeetStringResolver
import de.aarondietz.beetmeister.ui.core.component.BeetPullToRefreshBox
import de.aarondietz.beetmeister.ui.core.formatting.calibrationSourceLabel
import de.aarondietz.beetmeister.ui.core.formatting.formatUnixSeconds

@Composable
internal fun CalibrationScreen(
    state: BeetRepositoryState,
    onRefresh: () -> Unit,
    onSave: (Int, Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = rememberBeetStringResolver()
    LaunchedEffect(state.connection.phase) {
        if (state.connection.phase == BeetConnectionPhase.Connected) {
            onRefresh()
        }
    }
    BeetPullToRefreshBox(
        isRefreshing = state.calibrationsRefreshing,
        onRefresh = onRefresh,
        enabled = state.connection.phase == BeetConnectionPhase.Connected,
        modifier = modifier,
    ) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(strings.get(R.string.calibration_title), style = MaterialTheme.typography.headlineSmall)
                }
            }
            items(state.pairStates, key = { pair -> pair.pairIndex }) { pair ->
                val calibration = state.calibrations[pair.pairIndex]
                CalibrationCard(
                    pairState = pair,
                    dryValue = calibration?.dryMillivolts,
                    wetValue = calibration?.wetMillivolts,
                    source = calibration?.source,
                    calibratedAtUnixSeconds = calibration?.calibratedAtUnixSeconds ?: 0L,
                    onSave = { dry, wet -> onSave(pair.pairIndex, dry, wet) },
                    strings = strings,
                )
            }
        }
    }
}

@Composable
private fun CalibrationCard(
    pairState: BeetPairState,
    dryValue: Int?,
    wetValue: Int?,
    source: String?,
    calibratedAtUnixSeconds: Long,
    onSave: (Int, Int) -> Unit,
    strings: BeetStringResolver,
) {
    var dryText by rememberSaveable(pairState.pairIndex, dryValue) { mutableStateOf(dryValue?.toString().orEmpty()) }
    var wetText by rememberSaveable(pairState.pairIndex, wetValue) { mutableStateOf(wetValue?.toString().orEmpty()) }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = Color(0xFFF8F4EA)),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(strings.get(R.string.common_pair_number, pairState.pairIndex), style = MaterialTheme.typography.titleLarge)
                AssistChip(
                    onClick = {},
                    label = { Text(source?.let { calibrationSourceLabel(it, strings) } ?: strings.get(R.string.common_unknown)) },
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(strings.get(R.string.calibration_live_sensor_value, pairState.sensorMillivolts))
            if (calibratedAtUnixSeconds > 0) {
                Text(strings.get(R.string.calibration_calibrated_at, formatUnixSeconds(calibratedAtUnixSeconds, strings)))
            }
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = dryText,
                onValueChange = { input -> dryText = input.filter(Char::isDigit) },
                label = { Text(strings.get(R.string.calibration_dry_reference)) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = wetText,
                onValueChange = { input -> wetText = input.filter(Char::isDigit) },
                label = { Text(strings.get(R.string.calibration_wet_reference)) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(onClick = { dryText = pairState.sensorMillivolts.toString() }) {
                    Text(strings.get(R.string.calibration_capture_dry))
                }
                FilledTonalButton(onClick = { wetText = pairState.sensorMillivolts.toString() }) {
                    Text(strings.get(R.string.calibration_capture_wet))
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = {
                    val dry = dryText.toIntOrNull()
                    val wet = wetText.toIntOrNull()
                    if (dry != null && wet != null) {
                        onSave(dry, wet)
                    }
                },
            ) {
                Text(strings.get(R.string.calibration_save))
            }
        }
    }
}
