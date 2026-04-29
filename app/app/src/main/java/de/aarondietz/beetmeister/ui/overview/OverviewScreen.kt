package de.aarondietz.beetmeister.ui.overview

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
import de.aarondietz.beetmeister.beet.BeetPairState
import de.aarondietz.beetmeister.beet.BeetRepositoryState
import de.aarondietz.beetmeister.ui.composable.PairErrorClearButton
import de.aarondietz.beetmeister.ui.composable.PairEnabledToggleButton
import de.aarondietz.beetmeister.ui.composable.ValueGridRow
import de.aarondietz.beetmeister.ui.formatting.formatDuration
import de.aarondietz.beetmeister.ui.formatting.formatUnixSeconds
import de.aarondietz.beetmeister.ui.formatting.yesNo
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
            )
        }
    }
}

@Composable
private fun SystemValuesCard(state: BeetRepositoryState) {
    val device = state.deviceState ?: return
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMillis = System.currentTimeMillis()
            delay(60_000)
        }
    }
    val runningSinceMillis = if (state.connectedAtMillis > 0L) {
        state.connectedAtMillis - ((state.connectedAtControllerUptimeSeconds.coerceAtLeast(0L)) * 1000L)
    } else {
        nowMillis - (device.uptimeSeconds * 1000L)
    }
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = Color(0xFFF6F1E4)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Current system values", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(12.dp))
            ValueGridRow("Battery", "${device.batteryMillivolts} mV", "Approx.", "${device.batteryPercentApprox}%")
            ValueGridRow("Battery state", device.batteryState, "Next check", formatDuration(device.nextCheckInSeconds))
            ValueGridRow("Active pumps", device.activePumps.toString(), "Time valid", yesNo(device.timeValid))
            ValueGridRow("Wi-Fi", yesNo(device.wifiConnected), "MQTT", yesNo(device.mqttConnected))
            ValueGridRow("Running since", formatUnixSeconds(runningSinceMillis / 1000L), "Uptime", formatDuration(device.uptimeSeconds.toInt()))
        }
    }
}

@Composable
private fun PairOverviewCard(
    pair: BeetPairState,
    onDetails: () -> Unit,
    onClearError: () -> Unit,
    onToggleEnabled: () -> Unit,
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
                Text("Pair ${pair.pairIndex}", style = MaterialTheme.typography.titleLarge)
                AssistChip(onClick = {}, label = { Text(pair.state) })
            }
            Spacer(modifier = Modifier.height(10.dp))
            ValueGridRow("Moisture", "${pair.moisturePercent}%", "Sensor", "${pair.sensorMillivolts} mV")
            ValueGridRow("Source", pair.source, "Remaining", formatDuration(pair.remainingSeconds))
            if (!pair.enabled) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Disabled: excluded from watering and invalid-sensor alarms.",
                    color = Color(0xFF545454),
                    fontWeight = FontWeight.SemiBold,
                )
            } else if (pair.blocked || pair.state == "FAULT") {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Reason: ${pair.blockReason}",
                    color = Color(0xFF7D4632),
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onDetails) { Text("Details") }
                PairErrorClearButton(
                    canClearError = pair.sensorValid && (pair.blocked || pair.state == "FAULT"),
                    onClear = onClearError,
                )
                PairEnabledToggleButton(pairEnabled = pair.enabled, onToggle = onToggleEnabled)
            }
        }
    }
}
