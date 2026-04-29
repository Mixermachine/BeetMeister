package de.aarondietz.beetmeister.ui.pairdetail

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import de.aarondietz.beetmeister.beet.BeetPairState
import de.aarondietz.beetmeister.ui.composable.PairErrorClearButton
import de.aarondietz.beetmeister.ui.composable.PairEnabledToggleButton
import de.aarondietz.beetmeister.ui.composable.ValueGridRow
import de.aarondietz.beetmeister.ui.formatting.formatDuration
import de.aarondietz.beetmeister.ui.formatting.yesNo

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
            TextButton(onClick = onBack) { Text("Back") }
        }
        item {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(containerColor = Color(0xFFF9F6EF)),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Pair ${pairState.pairIndex}", style = MaterialTheme.typography.headlineSmall)
                    Spacer(modifier = Modifier.height(12.dp))
                    ValueGridRow("State", pairState.state, "Source", pairState.source)
                    ValueGridRow("Moisture", "${pairState.moisturePercent}%", "Sensor", "${pairState.sensorMillivolts} mV")
                    ValueGridRow("Enabled", yesNo(pairState.enabled), "Sensor valid", yesNo(pairState.sensorValid))
                    ValueGridRow("Blocked", yesNo(pairState.blocked), "Remaining", formatDuration(pairState.remainingSeconds))
                    if (!pairState.enabled) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text("This pair is disabled and excluded from watering and invalid-sensor alarms.", color = Color(0xFF545454))
                    } else if (pairState.blocked || pairState.state == "FAULT") {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text("Reason: ${pairState.blockReason}", color = Color(0xFF7D4632))
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
                    Text("Manual watering", style = MaterialTheme.typography.titleLarge)
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = durationText,
                        onValueChange = { input -> durationText = input.filter(Char::isDigit) },
                        label = { Text("Timed start (seconds)") },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = pairState.enabled,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        FilledTonalButton(
                            onClick = { onManualStart(pairState.pairIndex, null) },
                            enabled = pairState.enabled,
                        ) {
                            Text("Start default")
                        }
                        Button(
                            onClick = { onManualStart(pairState.pairIndex, durationText.toIntOrNull()) },
                            enabled = pairState.enabled,
                        ) {
                            Text("Start timed")
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(
                            onClick = { onManualStop(pairState.pairIndex) },
                            enabled = pairState.enabled,
                        ) {
                            Text("Stop")
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
                    Text("Irrigation detection", style = MaterialTheme.typography.titleLarge)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Runs the short moisture response check without starting a full watering cycle.",
                        color = Color(0xFF545454),
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = { onMoistureTestStart(pairState.pairIndex) },
                        enabled = canStartMoistureTest,
                    ) {
                        Text(if (pairState.state == "MOISTURE_TEST") "Testing..." else "Test detection")
                    }
                }
            }
        }
    }
}
