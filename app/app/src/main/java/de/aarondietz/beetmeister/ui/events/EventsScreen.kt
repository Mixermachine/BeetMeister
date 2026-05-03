package de.aarondietz.beetmeister.ui.events

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import de.aarondietz.beetmeister.beet.model.repository.BeetRepositoryState
import de.aarondietz.beetmeister.ui.events.composable.DurationBarChart
import de.aarondietz.beetmeister.ui.events.composable.SystemEventRow
import de.aarondietz.beetmeister.ui.events.composable.WateringEventRow

@Composable
internal fun EventsScreen(
    state: BeetRepositoryState,
    onLoadDetails: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var window by remember { mutableStateOf(WateringWindow.Day) }
    var filter by remember { mutableStateOf(EventFilter.All) }
    val nowSeconds = System.currentTimeMillis() / 1000L
    val totals = wateringTotals(state.recentEvents, window, nowSeconds)
    val filteredSystemEvents = state.systemEvents.filter { filter.acceptsSystem(it) }

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
                Text("Events", style = MaterialTheme.typography.headlineSmall)
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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        WateringWindow.entries.forEach { option ->
                            FilterChip(
                                selected = window == option,
                                onClick = { window = option },
                                label = { Text(option.label) },
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    DurationBarChart(totals)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Synced ${state.eventSync.downloaded}/${state.eventSync.total} events")
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(onClick = onLoadDetails) { Text("Details") }
                }
            }
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EventFilter.entries.forEach { option ->
                    FilterChip(
                        selected = filter == option,
                        onClick = { filter = option },
                        label = { Text(option.label) },
                    )
                }
            }
        }
        if (filter == EventFilter.Watering) {
            items(state.recentEvents.sortedByDescending { it.sequenceNumber }, key = { "w${it.sequenceNumber}" }) { event ->
                WateringEventRow(event = event, state = state)
            }
        } else {
            items(filteredSystemEvents.sortedByDescending { it.sequenceNumber }, key = { "s${it.sequenceNumber}" }) { event ->
                SystemEventRow(event = event, state = state)
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
            Text("Event table", style = MaterialTheme.typography.headlineSmall)
        }
        items(state.systemEvents.sortedByDescending { it.sequenceNumber }, key = { "sys${it.sequenceNumber}" }) { event ->
            SystemEventRow(event = event, state = state)
        }
        items(state.recentEvents.sortedByDescending { it.sequenceNumber }, key = { "wat${it.sequenceNumber}" }) { event ->
            WateringEventRow(event = event, state = state)
        }
    }
}
