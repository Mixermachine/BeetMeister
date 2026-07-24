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
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Slider
import de.aarondietz.beetmeister.model.controller.BeetPairConfig
import de.aarondietz.beetmeister.model.controller.TargetMoistureLevel
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Create
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import de.aarondietz.beetmeister.R
import de.aarondietz.beetmeister.model.controller.BeetPairState
import de.aarondietz.beetmeister.model.controller.BeetPairWiring
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
import de.aarondietz.beetmeister.ui.core.preview.PreviewData
import de.aarondietz.beetmeister.ui.core.theme.BeetMeisterTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.tooling.preview.Preview

@Composable
internal fun PairDetailScreen(
    pairState: BeetPairState,
    pairWiring: BeetPairWiring?,
    pairWiringLoading: Boolean,
    pairWiringError: String?,
    pairName: String?,
    onStorePairName: (Int, String) -> Unit,
    onBack: () -> Unit,
    onLoadPairWiring: (Int) -> Unit,
    onToggleEnabled: (Int) -> Unit,
    onManualStart: (Int, Int?) -> Unit,
    onManualStop: (Int) -> Unit,
    onMoistureTestStart: (Int) -> Unit,
    onClearError: (Int) -> Unit,
    pairConfig: BeetPairConfig? = null,
    onLoadPairConfig: (Int) -> Unit = {},
    onStorePairConfig: (Int, TargetMoistureLevel, Int) -> Unit = { _, _, _ -> },
    showRenameDialogDefault: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val strings = rememberBeetStringResolver()
    var durationText by rememberSaveable(pairState.pairIndex) { mutableStateOf("") }
    var showRenameDialog by remember { mutableStateOf(showRenameDialogDefault) }
    var renameText by remember(pairName) { mutableStateOf(pairName ?: "") }
    val canStartMoistureTest = pairState.enabled &&
        pairState.sensorValid &&
        !pairState.blocked &&
        pairState.state !in setOf("FAULT", "WATERING", "SANITY_CHECK", "MOISTURE_TEST", "WAITING_FOR_SLOT")

    LaunchedEffect(pairState.pairIndex) {
        onLoadPairWiring(pairState.pairIndex)
        onLoadPairConfig(pairState.pairIndex)
    }

    LazyColumn(
        modifier = modifier.testTag(PairDetailTestTags.Container),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            TextButton(
                onClick = onBack,
                modifier = Modifier.testTag(PairDetailTestTags.BackButton),
            ) { Text(strings.get(R.string.common_back)) }
        }
        item {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(containerColor = Color(0xFFF9F6EF)),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Text(
                            if (pairName != null && pairName.isNotBlank()) pairName
                            else strings.get(R.string.common_pair_number, pairState.pairIndex),
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier
                                .weight(1f)
                                .testTag(PairDetailTestTags.Name),
                        )
                        IconButton(
                            onClick = {
                                renameText = pairName ?: ""
                                showRenameDialog = true
                            },
                            modifier = Modifier.testTag(PairDetailTestTags.RenameButton),
                        ) {
                            Icon(imageVector = Icons.Default.Create, contentDescription = strings.get(R.string.pair_detail_rename_title))
                        }
                    }
                    if (showRenameDialog) {
                        RenamePairDialog(
                            renameText = renameText,
                            onRenameTextChange = { input ->
                                renameText = if (input.length > 15) input.take(15) else input
                            },
                            onSave = {
                                val trimmed = renameText.trim()
                                onStorePairName(pairState.pairIndex, trimmed)
                                showRenameDialog = false
                            },
                            onDismiss = { showRenameDialog = false },
                            strings = strings,
                        )
                    }
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
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(strings.get(R.string.pair_detail_wiring_title), style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    when {
                        pairWiring != null -> {
                            ValueGridRow(
                                strings.get(R.string.pair_detail_label_moisture_gpio),
                                pairWiring.moistureGpio.toString(),
                                strings.get(R.string.pair_detail_label_relay_gpio),
                                pairWiring.relayGpio.toString(),
                            )
                        }
                        pairWiringLoading -> {
                            Text(strings.get(R.string.pair_detail_wiring_loading), color = Color(0xFF545454))
                        }
                        pairWiringError != null -> {
                            Text(pairWiringError, color = Color(0xFF7D4632))
                            TextButton(onClick = { onLoadPairWiring(pairState.pairIndex) }) {
                                Text(strings.get(R.string.pair_detail_wiring_retry))
                            }
                        }
                    }
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
                        modifier = Modifier.testTag(PairDetailTestTags.EnabledToggle),
                    )
                }
            }
        }
        item {
            PairConfigCard(
                pairIndex = pairState.pairIndex,
                pairConfig = pairConfig,
                onStorePairConfig = onStorePairConfig,
            )
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

@Composable
private fun PairConfigCard(
    pairIndex: Int,
    pairConfig: BeetPairConfig?,
    onStorePairConfig: (Int, TargetMoistureLevel, Int) -> Unit,
) {
    val currentLevel = pairConfig?.targetLevel ?: TargetMoistureLevel.MEDIUM
    val currentMultFloat = pairConfig?.multiplierFloat ?: 1.0f

    var selectedLevel by remember(pairConfig) { mutableStateOf(currentLevel) }
    var sliderValue by remember(pairConfig) { mutableStateOf(currentMultFloat) }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = Color(0xFFF3EFE0)),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Target Moisture & Watering Time",
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Target Moisture Level:",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                FilterChip(
                    selected = selectedLevel == TargetMoistureLevel.DRY,
                    onClick = {
                        selectedLevel = TargetMoistureLevel.DRY
                        onStorePairConfig(
                            pairIndex,
                            TargetMoistureLevel.DRY,
                            (sliderValue * 100).toInt(),
                        )
                    },
                    label = { Text("Dry (~40%)") },
                    modifier = Modifier.testTag(PairDetailTestTags.TargetLevelDry),
                )
                FilterChip(
                    selected = selectedLevel == TargetMoistureLevel.MEDIUM,
                    onClick = {
                        selectedLevel = TargetMoistureLevel.MEDIUM
                        onStorePairConfig(
                            pairIndex,
                            TargetMoistureLevel.MEDIUM,
                            (sliderValue * 100).toInt(),
                        )
                    },
                    label = { Text("Medium (~50%)") },
                    modifier = Modifier.testTag(PairDetailTestTags.TargetLevelMedium),
                )
                FilterChip(
                    selected = selectedLevel == TargetMoistureLevel.MOIST,
                    onClick = {
                        selectedLevel = TargetMoistureLevel.MOIST
                        onStorePairConfig(
                            pairIndex,
                            TargetMoistureLevel.MOIST,
                            (sliderValue * 100).toInt(),
                        )
                    },
                    label = { Text("Moist (~65%)") },
                    modifier = Modifier.testTag(PairDetailTestTags.TargetLevelMoist),
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Watering Time Multiplier: ${String.format("%.1fx", sliderValue)}",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Slider(
                value = sliderValue,
                onValueChange = { newValue ->
                    sliderValue = (Math.round(newValue * 10) / 10.0f).coerceIn(0.2f, 2.0f)
                },
                onValueChangeFinished = {
                    onStorePairConfig(
                        pairIndex,
                        selectedLevel,
                        (sliderValue * 100).toInt(),
                    )
                },
                valueRange = 0.2f..2.0f,
                steps = 17,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(PairDetailTestTags.MultiplierSlider),
            )
            Text(
                text = "Scales automatic watering duration by ${String.format("%.1fx", sliderValue)}.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF545454),
            )
        }
    }
}

@Composable
internal fun RenamePairDialog(
    renameText: String,
    onRenameTextChange: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    strings: de.aarondietz.beetmeister.strings.BeetStringResolver,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(PairDetailTestTags.RenameDialog),
        title = { Text(strings.get(R.string.pair_detail_rename_title)) },
        text = {
            OutlinedTextField(
                value = renameText,
                onValueChange = onRenameTextChange,
                label = { Text(strings.get(R.string.pair_detail_rename_label)) },
                singleLine = true,
                modifier = Modifier.testTag(PairDetailTestTags.RenameDialogTextField),
            )
        },
        confirmButton = {
            TextButton(
                onClick = onSave,
                modifier = Modifier.testTag(PairDetailTestTags.RenameDialogSave),
            ) {
                Text(strings.get(R.string.pair_detail_rename_save))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag(PairDetailTestTags.RenameDialogCancel),
            ) {
                Text(strings.get(R.string.common_cancel))
            }
        },
    )
}

// region Previews

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true, backgroundColor = 0xFFF6F1E4)
@Composable
private fun PairDetailScreenPreview_Idle() {
    BeetMeisterTheme {
        PairDetailScreen(
            pairState = PreviewData.pairStateIdle(pairIndex = 1, moisturePercent = 58, sensorMillivolts = 1520),
            pairWiring = PreviewData.pairWiring(1),
            pairWiringLoading = false,
            pairWiringError = null,
            pairName = null,
            onStorePairName = { _, _ -> },
            onBack = {},
            onLoadPairWiring = {},
            onToggleEnabled = {},
            onManualStart = { _, _ -> },
            onManualStop = {},
            onMoistureTestStart = {},
            onClearError = {},
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true, backgroundColor = 0xFFF6F1E4)
@Composable
private fun PairDetailScreenPreview_Watering() {
    BeetMeisterTheme {
        PairDetailScreen(
            pairState = PreviewData.pairStateWatering(
                pairIndex = 2,
                moisturePercent = 42,
                sensorMillivolts = 1280,
                remainingSeconds = 87,
            ),
            pairWiring = PreviewData.pairWiring(2),
            pairWiringLoading = false,
            pairWiringError = null,
            pairName = null,
            onStorePairName = { _, _ -> },
            onBack = {},
            onLoadPairWiring = {},
            onToggleEnabled = {},
            onManualStart = { _, _ -> },
            onManualStop = {},
            onMoistureTestStart = {},
            onClearError = {},
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true, backgroundColor = 0xFFF6F1E4)
@Composable
private fun PairDetailScreenPreview_Fault() {
    BeetMeisterTheme {
        PairDetailScreen(
            pairState = PreviewData.pairStateFault(
                pairIndex = 3,
                blockReason = "SENSOR_READING_INVALID",
            ),
            pairWiring = PreviewData.pairWiring(3),
            pairWiringLoading = false,
            pairWiringError = null,
            pairName = null,
            onStorePairName = { _, _ -> },
            onBack = {},
            onLoadPairWiring = {},
            onToggleEnabled = {},
            onManualStart = { _, _ -> },
            onManualStop = {},
            onMoistureTestStart = {},
            onClearError = {},
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true, backgroundColor = 0xFFF6F1E4)
@Composable
private fun PairDetailScreenPreview_Disabled() {
    BeetMeisterTheme {
        PairDetailScreen(
            pairState = PreviewData.pairStateDisabled(5),
            pairWiring = PreviewData.pairWiring(5),
            pairWiringLoading = false,
            pairWiringError = null,
            pairName = null,
            onStorePairName = { _, _ -> },
            onBack = {},
            onLoadPairWiring = {},
            onToggleEnabled = {},
            onManualStart = { _, _ -> },
            onManualStop = {},
            onMoistureTestStart = {},
            onClearError = {},
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true, backgroundColor = 0xFFF6F1E4)
@Composable
private fun PairDetailScreenPreview_WiringLoading() {
    BeetMeisterTheme {
        PairDetailScreen(
            pairState = PreviewData.pairStateIdle(pairIndex = 1),
            pairWiring = null,
            pairWiringLoading = true,
            pairWiringError = null,
            pairName = null,
            onStorePairName = { _, _ -> },
            onBack = {},
            onLoadPairWiring = {},
            onToggleEnabled = {},
            onManualStart = { _, _ -> },
            onManualStop = {},
            onMoistureTestStart = {},
            onClearError = {},
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true, backgroundColor = 0xFFF6F1E4)
@Composable
private fun PairDetailScreenPreview_WiringError() {
    BeetMeisterTheme {
        PairDetailScreen(
            pairState = PreviewData.pairStateIdle(pairIndex = 1),
            pairWiring = null,
            pairWiringLoading = false,
            pairWiringError = "Wiring info request timed out after 5s.",
            pairName = null,
            onStorePairName = { _, _ -> },
            onBack = {},
            onLoadPairWiring = {},
            onToggleEnabled = {},
            onManualStart = { _, _ -> },
            onManualStop = {},
            onMoistureTestStart = {},
            onClearError = {},
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true, backgroundColor = 0xFFF6F1E4)
@Composable
private fun PairDetailScreenPreview_WithName() {
    BeetMeisterTheme {
        PairDetailScreen(
            pairState = PreviewData.pairStateIdle(pairIndex = 1, moisturePercent = 64, sensorMillivolts = 1400),
            pairWiring = PreviewData.pairWiring(1),
            pairWiringLoading = false,
            pairWiringError = null,
            pairName = "Front Garden",
            onStorePairName = { _, _ -> },
            onBack = {},
            onLoadPairWiring = {},
            onToggleEnabled = {},
            onManualStart = { _, _ -> },
            onManualStop = {},
            onMoistureTestStart = {},
            onClearError = {},
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true, backgroundColor = 0xFFF6F1E4)
@Composable
private fun PairDetailScreenPreview_RenameDialogOpen() {
    BeetMeisterTheme {
        PairDetailScreen(
            pairState = PreviewData.pairStateIdle(pairIndex = 1, moisturePercent = 64, sensorMillivolts = 1400),
            pairWiring = PreviewData.pairWiring(1),
            pairWiringLoading = false,
            pairWiringError = null,
            pairName = "Front Garden",
            onStorePairName = { _, _ -> },
            onBack = {},
            onLoadPairWiring = {},
            onToggleEnabled = {},
            onManualStart = { _, _ -> },
            onManualStop = {},
            onMoistureTestStart = {},
            showRenameDialogDefault = true,
            onClearError = {},
        )
    }
}

// endregion
