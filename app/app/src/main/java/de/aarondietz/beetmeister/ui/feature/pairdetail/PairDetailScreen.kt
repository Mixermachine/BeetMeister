package de.aarondietz.beetmeister.ui.feature.pairdetail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.width
import androidx.compose.material3.RadioButton
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Slider
import de.aarondietz.beetmeister.model.controller.BeetPairConfig
import de.aarondietz.beetmeister.model.controller.TargetMoistureLevel
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Create
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
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
import de.aarondietz.beetmeister.model.controller.BeetPairCombined
import de.aarondietz.beetmeister.model.repository.leadFor
import de.aarondietz.beetmeister.model.repository.followersFor
import de.aarondietz.beetmeister.model.repository.availableLeadsFor
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
    pairState: BeetPairState?,
    pairIndex: Int = pairState?.pairIndex ?: 0,
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
    pairCombined: Map<Int, BeetPairCombined> = emptyMap(),
    isPairCombinedLoaded: Boolean = true,
    pairNames: Map<Int, String> = emptyMap(),
    displayedPairCount: Int = 8,
    onLoadPairCombined: (Int) -> Unit = {},
    onSetPairSensorSource: (Int, Int?) -> Unit = { _, _ -> },
    showRenameDialogDefault: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val strings = rememberBeetStringResolver()
    var durationText by rememberSaveable(pairIndex) { mutableStateOf("") }
    var showRenameDialog by remember { mutableStateOf(showRenameDialogDefault) }
    var renameText by remember(pairName) { mutableStateOf(pairName ?: "") }
    val canStartMoistureTest = pairState != null &&
        pairState.enabled &&
        pairState.sensorValid &&
        !pairState.blocked &&
        pairState.state !in setOf("FAULT", "WATERING", "SANITY_CHECK", "MOISTURE_TEST", "WAITING_FOR_SLOT")

    LaunchedEffect(pairIndex) {
        onLoadPairWiring(pairIndex)
        onLoadPairConfig(pairIndex)
        onLoadPairCombined(pairIndex)
    }

    LazyColumn(
        modifier = modifier.testTag(PairDetailTestTags.Container),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.testTag(PairDetailTestTags.BackButton),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = strings.get(R.string.common_back),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Text(
                    text = strings.get(R.string.common_back),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        item {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
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
                                onStorePairName(pairIndex, trimmed)
                                showRenameDialog = false
                            },
                            onDismiss = { showRenameDialog = false },
                            strings = strings,
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    ValueGridRow(
                        strings.get(R.string.pair_detail_label_state),
                        pairState?.let { pairStateLabel(it.state, strings) },
                        strings.get(R.string.pair_detail_label_source),
                        pairState?.let { runSourceLabel(it.source, strings) },
                    )
                    ValueGridRow(
                        strings.get(R.string.pair_detail_label_moisture),
                        pairState?.let { formatPercent(it.moisturePercent, strings) },
                        strings.get(R.string.pair_detail_label_sensor),
                        pairState?.let { formatMillivolts(it.sensorMillivolts, strings) },
                        leftValueModifier = Modifier.testTag(PairDetailTestTags.MoistureValue),
                        rightValueModifier = Modifier.testTag(PairDetailTestTags.SensorValue),
                    )
                    ValueGridRow(
                        strings.get(R.string.pair_detail_label_enabled),
                        pairState?.let { yesNo(it.enabled, strings) },
                        strings.get(R.string.pair_detail_label_sensor_valid),
                        pairState?.let { yesNo(it.sensorValid, strings) },
                    )
                    ValueGridRow(
                        strings.get(R.string.pair_detail_label_blocked),
                        pairState?.let { yesNo(it.blocked, strings) },
                        strings.get(R.string.pair_detail_label_remaining),
                        pairState?.let { formatDuration(it.remainingSeconds, strings) },
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
                            Text(strings.get(R.string.pair_detail_wiring_loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        pairWiringError != null -> {
                            Text(pairWiringError, color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = { onLoadPairWiring(pairIndex) }) {
                                Text(strings.get(R.string.pair_detail_wiring_retry))
                            }
                        }
                    }
                    pairState?.let { st ->
                        if (!st.enabled) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(strings.get(R.string.pair_detail_disabled_info), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else if (st.blocked || st.state == "FAULT") {
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                strings.get(R.string.common_reason_value, blockReasonCodeLabel(st.blockReason, strings)),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    PairEnabledToggleButton(
                        pairEnabled = pairState?.enabled ?: true,
                        onToggle = { onToggleEnabled(pairIndex) },
                        enabled = pairState != null,
                        modifier = Modifier.testTag(PairDetailTestTags.EnabledToggle),
                    )
                }
            }
        }
        item {
            PairConfigCard(
                pairIndex = pairIndex,
                pairConfig = pairConfig,
                onStorePairConfig = onStorePairConfig,
            )
        }
        item {
            SensorSourceCard(
                pairIndex = pairIndex,
                pairCombined = pairCombined,
                isPairCombinedLoaded = isPairCombinedLoaded,
                pairNames = pairNames,
                displayedPairCount = displayedPairCount,
                onSetPairSensorSource = onSetPairSensorSource,
                strings = strings,
            )
        }
        item {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(strings.get(R.string.pair_detail_manual_watering), style = MaterialTheme.typography.titleLarge)
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = durationText,
                        onValueChange = { input -> durationText = input.filter(Char::isDigit) },
                        label = { Text(strings.get(R.string.pair_detail_timed_start_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = pairState?.enabled ?: false,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        FilledTonalButton(
                            onClick = { onManualStart(pairIndex, null) },
                            enabled = pairState?.enabled ?: false,
                            modifier = Modifier.testTag(PairDetailTestTags.ManualStartButton),
                        ) {
                            Text(strings.get(R.string.pair_detail_start_default))
                        }
                        Button(
                            onClick = { onManualStart(pairIndex, durationText.toIntOrNull()) },
                            enabled = pairState?.enabled ?: false,
                        ) {
                            Text(strings.get(R.string.pair_detail_start_timed))
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(
                            onClick = { onManualStop(pairIndex) },
                            enabled = pairState?.enabled ?: false,
                        ) {
                            Text(strings.get(R.string.pair_detail_stop))
                        }
                        PairErrorClearButton(
                            canClearError = pairState != null &&
                                pairState.sensorValid &&
                                (pairState.blocked || pairState.state == "FAULT"),
                            onClear = { onClearError(pairIndex) },
                        )
                    }
                }
            }
        }
        item {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(strings.get(R.string.pair_detail_irrigation_detection), style = MaterialTheme.typography.titleLarge)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        strings.get(R.string.pair_detail_detection_description),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = { onMoistureTestStart(pairIndex) },
                        enabled = canStartMoistureTest,
                        modifier = Modifier.testTag(PairDetailTestTags.MoistureTestButton),
                    ) {
                        Text(
                            strings.get(
                                if (pairState?.state == "MOISTURE_TEST") {
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

    var selectedLevel by remember(pairIndex) { mutableStateOf(currentLevel) }
    var sliderValue by remember(pairIndex) { mutableStateOf(currentMultFloat) }

    LaunchedEffect(pairConfig?.targetLevel) {
        if (pairConfig?.targetLevel != null) {
            selectedLevel = pairConfig.targetLevel
        }
    }

    LaunchedEffect(pairConfig?.multiplierFloat) {
        if (pairConfig?.multiplierFloat != null) {
            sliderValue = pairConfig.multiplierFloat
        }
    }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
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
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SensorSourceCard(
    pairIndex: Int,
    pairCombined: Map<Int, BeetPairCombined>,
    isPairCombinedLoaded: Boolean,
    pairNames: Map<Int, String>,
    displayedPairCount: Int,
    onSetPairSensorSource: (Int, Int?) -> Unit,
    strings: de.aarondietz.beetmeister.strings.BeetStringResolver,
) {
    val tempState = de.aarondietz.beetmeister.model.repository.BeetRepositoryState(
        controllerInfo = de.aarondietz.beetmeister.model.controller.BeetControllerInfo(
            deviceId = "dummy",
            protocolVersion = 0,
            firmwareVersion = "0.0.0",
            pairCount = displayedPairCount,
        ),
        pairCombined = pairCombined,
        isPairCombinedLoaded = isPairCombinedLoaded,
        pairNames = pairNames,
    )

    val currentLead = tempState.leadFor(pairIndex)
    val followers = tempState.followersFor(pairIndex)
    val isLead = followers.isNotEmpty()
    val availableLeads = tempState.availableLeadsFor(pairIndex)

    var expandedDropdown by remember { mutableStateOf(false) }

    fun pairLabel(idx: Int): String {
        val customName = pairNames[idx]
        return if (!customName.isNullOrBlank()) "$customName (${strings.get(R.string.common_pair_number, idx)})"
        else strings.get(R.string.common_pair_number, idx)
    }

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(PairDetailTestTags.SensorSourceCard),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = strings.get(R.string.pair_detail_sensor_source_title),
                style = MaterialTheme.typography.titleLarge,
            )
            if (!isPairCombinedLoaded) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.testTag(PairDetailTestTags.SensorSourceLoading),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Text(
                        text = strings.get(R.string.pair_detail_sensor_source_loading),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))

            if (isLead) {
                val followerNames = followers.joinToString(", ") { pairLabel(it) }
                Text(
                    text = strings.get(R.string.pair_detail_sensor_source_lead_desc, followerNames),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val dedicatedEnabled = isPairCombinedLoaded
                val onSelectDedicated = {
                    if (dedicatedEnabled && currentLead != null) {
                        onSetPairSensorSource(pairIndex, null)
                    }
                }
                val sharedEnabled = isPairCombinedLoaded && (availableLeads.isNotEmpty() || currentLead != null)
                val onSelectShared = {
                    if (sharedEnabled && currentLead == null && availableLeads.isNotEmpty()) {
                        onSetPairSensorSource(pairIndex, availableLeads.first())
                    }
                }

                // Radio option: Dedicated sensor
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = currentLead == null,
                        onClick = onSelectDedicated,
                        enabled = dedicatedEnabled,
                        modifier = Modifier.testTag(PairDetailTestTags.SensorSourceDedicatedRadio),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clickable(
                                enabled = dedicatedEnabled,
                                role = Role.RadioButton,
                                onClick = onSelectDedicated,
                            ),
                    ) {
                        Text(
                            text = strings.get(R.string.pair_detail_sensor_source_dedicated),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = strings.get(R.string.pair_detail_sensor_source_dedicated_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Radio option: Share from another pair
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = currentLead != null,
                        onClick = onSelectShared,
                        enabled = sharedEnabled,
                        modifier = Modifier.testTag(PairDetailTestTags.SensorSourceSharedRadio),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clickable(
                                enabled = sharedEnabled,
                                role = Role.RadioButton,
                                onClick = onSelectShared,
                            ),
                    ) {
                        Text(
                            text = strings.get(R.string.pair_detail_sensor_source_shared),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        if (currentLead != null) {
                            Text(
                                text = strings.get(
                                    R.string.pair_detail_sensor_source_follower_desc,
                                    pairLabel(currentLead),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else if (availableLeads.isEmpty() && isPairCombinedLoaded) {
                            Text(
                                text = strings.get(R.string.pair_detail_sensor_source_no_leads_available),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }

                // Lead picker dropdown (only when Shared is selected)
                if (currentLead != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    ExposedDropdownMenuBox(
                        expanded = expandedDropdown && isPairCombinedLoaded,
                        onExpandedChange = { if (isPairCombinedLoaded) expandedDropdown = it },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        OutlinedTextField(
                            value = pairLabel(currentLead),
                            onValueChange = {},
                            readOnly = true,
                            enabled = isPairCombinedLoaded,
                            label = { Text(strings.get(R.string.pair_detail_sensor_source_lead_picker_label)) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedDropdown) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                                .testTag(PairDetailTestTags.SensorSourceDropdown),
                        )
                        ExposedDropdownMenu(
                            expanded = expandedDropdown && isPairCombinedLoaded,
                            onDismissRequest = { expandedDropdown = false },
                        ) {
                            availableLeads.forEach { leadIdx ->
                                DropdownMenuItem(
                                    text = { Text(pairLabel(leadIdx)) },
                                    onClick = {
                                        expandedDropdown = false
                                        onSetPairSensorSource(pairIndex, leadIdx)
                                    },
                                    modifier = Modifier.testTag(PairDetailTestTags.SensorSourceDropdownItem + leadIdx),
                                )
                            }
                        }
                    }
                }
            }
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
