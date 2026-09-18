package de.aarondietz.beetmeister.ui.feature.calibration

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import de.aarondietz.beetmeister.R
import de.aarondietz.beetmeister.model.connection.BeetConnectionPhase
import de.aarondietz.beetmeister.model.controller.BeetPairState
import de.aarondietz.beetmeister.model.repository.BeetRepositoryState
import de.aarondietz.beetmeister.model.repository.displayedPairCount
import de.aarondietz.beetmeister.strings.BeetStringResolver
import de.aarondietz.beetmeister.strings.rememberBeetStringResolver
import de.aarondietz.beetmeister.ui.core.component.BeetPullToRefreshBox
import de.aarondietz.beetmeister.ui.core.formatting.calibrationSourceLabel
import de.aarondietz.beetmeister.ui.core.formatting.formatUnixSeconds

internal data class CalibrationSaveDraft(
    val pairIndex: Int,
    val dryMillivolts: Int,
    val wetMillivolts: Int,
)

@Composable
internal fun CalibrationScreen(
    state: BeetRepositoryState,
    onRefresh: () -> Unit,
    onSave: (Int, Int, Int) -> Unit,
    onUnsavedStateChange: (Boolean, List<CalibrationSaveDraft>?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = rememberBeetStringResolver()
    var dryTexts by rememberSaveable { mutableStateOf<Map<Int, String>>(emptyMap()) }
    var wetTexts by rememberSaveable { mutableStateOf<Map<Int, String>>(emptyMap()) }
    LaunchedEffect(state.connection.phase) {
        if (state.connection.phase == BeetConnectionPhase.Connected) {
            onRefresh()
        }
    }
    // Text fields mirror calibration data only. Live pair-state pushes cannot
    // clobber in-progress input; a (re)load of calibrations re-seeds the fields.
    LaunchedEffect(state.calibrations) {
        dryTexts = state.calibrations.mapValues { it.value.dryMillivolts.toString() }
        wetTexts = state.calibrations.mapValues { it.value.wetMillivolts.toString() }
    }

    val pairRange = (1..state.displayedPairCount).toList()
    val dirtyDrafts = pairRange.mapNotNull { index ->
        val calibration = state.calibrations[index]
        val currentDry = dryTexts[index].orEmpty()
        val currentWet = wetTexts[index].orEmpty()
        val loadedDry = calibration?.dryMillivolts?.toString().orEmpty()
        val loadedWet = calibration?.wetMillivolts?.toString().orEmpty()
        if (currentDry == loadedDry && currentWet == loadedWet) {
            null
        } else {
            val dry = currentDry.toIntOrNull()
            val wet = currentWet.toIntOrNull()
            CalibrationSaveDraft(
                pairIndex = index,
                dryMillivolts = dry ?: 0,
                wetMillivolts = wet ?: 0,
            ) to (dry != null && wet != null && dry > wet && wet > 0)
        }
    }
    val hasUnsavedChanges = dirtyDrafts.isNotEmpty()
    val savableDrafts = if (!hasUnsavedChanges) {
        null
    } else if (dirtyDrafts.any { !it.second }) {
        null
    } else {
        dirtyDrafts.map { it.first }
    }

    LaunchedEffect(hasUnsavedChanges, savableDrafts) {
        onUnsavedStateChange(hasUnsavedChanges, savableDrafts)
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
            items(pairRange, key = { pairIndex -> pairIndex }) { index ->
                val pairState = state.pairStates[index]
                val calibration = state.calibrations[index]
                CalibrationCard(
                    pairIndex = index,
                    pairState = pairState,
                    pairName = state.pairNames[index],
                    dryValue = calibration?.dryMillivolts,
                    wetValue = calibration?.wetMillivolts,
                    dryText = dryTexts[index].orEmpty(),
                    wetText = wetTexts[index].orEmpty(),
                    source = calibration?.source,
                    calibratedAtUnixSeconds = calibration?.calibratedAtUnixSeconds ?: 0L,
                    onDryChange = { updated -> dryTexts = dryTexts + (index to updated) },
                    onWetChange = { updated -> wetTexts = wetTexts + (index to updated) },
                    onSave = { dry, wet -> onSave(index, dry, wet) },
                    strings = strings,
                )
            }
        }
    }
}

@Composable
private fun CalibrationCard(
    pairIndex: Int,
    pairState: BeetPairState?,
    pairName: String?,
    dryValue: Int?,
    wetValue: Int?,
    dryText: String,
    wetText: String,
    source: String?,
    calibratedAtUnixSeconds: Long,
    onDryChange: (String) -> Unit,
    onWetChange: (String) -> Unit,
    onSave: (Int, Int) -> Unit,
    strings: BeetStringResolver,
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(CalibrationTestTags.card(pairIndex)),
        colors = CardDefaults.elevatedCardColors(containerColor = Color(0xFFF8F4EA)),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (pairName != null && pairName.isNotBlank()) pairName
                    else strings.get(R.string.common_pair_number, pairIndex),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.testTag(CalibrationTestTags.title(pairIndex)),
                )
                AssistChip(
                    onClick = {},
                    label = { Text(source?.let { calibrationSourceLabel(it, strings) } ?: strings.get(R.string.common_unknown)) },
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            if (pairState != null) {
                Text(strings.get(R.string.calibration_live_sensor_value, pairState.sensorMillivolts))
            } else {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                )
            }
            if (calibratedAtUnixSeconds > 0) {
                Text(strings.get(R.string.calibration_calibrated_at, formatUnixSeconds(calibratedAtUnixSeconds, strings)))
            }
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = dryText,
                onValueChange = { input -> onDryChange(input.filter(Char::isDigit)) },
                label = { Text(strings.get(R.string.calibration_dry_reference)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(CalibrationTestTags.dryInput(pairIndex)),
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = wetText,
                onValueChange = { input -> onWetChange(input.filter(Char::isDigit)) },
                label = { Text(strings.get(R.string.calibration_wet_reference)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(CalibrationTestTags.wetInput(pairIndex)),
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(
                    onClick = { pairState?.let { onDryChange(it.sensorMillivolts.toString()) } },
                    enabled = pairState != null,
                    modifier = Modifier.testTag(CalibrationTestTags.captureDryButton(pairIndex)),
                ) {
                    Text(strings.get(R.string.calibration_capture_dry))
                }
                FilledTonalButton(
                    onClick = { pairState?.let { onWetChange(it.sensorMillivolts.toString()) } },
                    enabled = pairState != null,
                    modifier = Modifier.testTag(CalibrationTestTags.captureWetButton(pairIndex)),
                ) {
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
                modifier = Modifier.testTag(CalibrationTestTags.saveButton(pairIndex)),
            ) {
                Text(strings.get(R.string.calibration_save))
            }
        }
    }
}
