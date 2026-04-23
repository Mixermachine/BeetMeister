package de.aarondietz.beetmeister.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.aarondietz.beetmeister.beet.BeetEventMappings
import de.aarondietz.beetmeister.beet.BeetRepositoryState
import de.aarondietz.beetmeister.beet.BeetWateringEvent
import kotlin.math.max

@Composable
internal fun EventsScreen(
    state: BeetRepositoryState,
    onLoadDetails: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val summary = state.historySummary
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Watering events", style = MaterialTheme.typography.headlineSmall)
                TextButton(onClick = onRefresh) { Text("Refresh") }
            }
        }
        item {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(containerColor = Color(0xFFEDF2F5)),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Watering time by pair", style = MaterialTheme.typography.titleLarge)
                    Spacer(modifier = Modifier.height(12.dp))
                    if (summary == null) {
                        Text("History summary is not loaded yet.")
                    } else {
                        Text("Latest event: ${summary.latestSequenceNumber}")
                        Text("Retained events: ${summary.eventCount}")
                        Spacer(modifier = Modifier.height(12.dp))
                        DurationBarChart(summary.pairTotalsSeconds)
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = onLoadDetails) { Text("Details") }
                }
            }
        }
    }
}

@Composable
internal fun EventDetailScreen(
    state: BeetRepositoryState,
    onBack: () -> Unit,
    onReload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) { Text("Back") }
                TextButton(onClick = onReload) { Text("Reload") }
            }
        }
        item {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(containerColor = Color(0xFFF7F3EA)),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Event table", style = MaterialTheme.typography.headlineSmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Newest retained events first.")
                }
            }
        }
        if (state.eventsLoading) {
            item {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        } else if (state.recentEvents.isEmpty()) {
            item {
                Text("No events loaded.")
            }
        } else {
            items(state.recentEvents.sortedByDescending { it.sequenceNumber }, key = { event -> event.sequenceNumber }) { event ->
                EventRow(event = event)
            }
        }
    }
}

@Composable
private fun DurationBarChart(pairTotalsSeconds: List<Int>) {
    val maxValue = max(pairTotalsSeconds.maxOrNull() ?: 0, 1)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        pairTotalsSeconds.forEachIndexed { index, totalSeconds ->
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Pair ${index + 1}")
                    Text(formatDuration(totalSeconds))
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(18.dp)
                        .background(Color(0xFFDDE4D7), RoundedCornerShape(50)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(totalSeconds.toFloat() / maxValue.toFloat())
                            .height(18.dp)
                            .background(Color(0xFF6B8F52), RoundedCornerShape(50)),
                    )
                }
            }
        }
    }
}

@Composable
private fun EventRow(event: BeetWateringEvent) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = Color(0xFFFDFBF6)),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Seq ${event.sequenceNumber}", fontWeight = FontWeight.SemiBold)
                Text("Pair ${event.pairIndex}")
            }
            Spacer(modifier = Modifier.height(8.dp))
            ValueGridRow("Source", BeetEventMappings.triggerSourceLabel(event.triggerSource), "Actual", formatDuration(event.actualDurationSeconds))
            ValueGridRow("Requested", formatDuration(event.requestedDurationSeconds), "Stop", BeetEventMappings.stopReasonLabel(event.stopReason))
            ValueGridRow("Block", BeetEventMappings.blockReasonLabel(event.blockReason), "Time", formatEventTime(event))
            ValueGridRow("Before", "${event.moistureBeforePercent}% / ${event.sensorBeforeMillivolts} mV", "After", "${event.moistureAfterPercent}% / ${event.sensorAfterMillivolts} mV")
        }
    }
}
