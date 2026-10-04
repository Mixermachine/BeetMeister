package de.aarondietz.beetmeister.ui.feature.events

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import de.aarondietz.beetmeister.ui.core.component.BeetLazyColumn
import de.aarondietz.beetmeister.ui.core.component.BeetCard
import de.aarondietz.beetmeister.ui.core.theme.spacing
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.aarondietz.beetmeister.R
import de.aarondietz.beetmeister.model.connection.BeetConnectionPhase
import de.aarondietz.beetmeister.model.event.BeetWateringEvent
import de.aarondietz.beetmeister.model.repository.BeetRepositoryState
import de.aarondietz.beetmeister.strings.BeetStringResolver
import de.aarondietz.beetmeister.strings.rememberBeetStringResolver
import de.aarondietz.beetmeister.ui.core.component.BeetPullToRefreshBox
import de.aarondietz.beetmeister.ui.feature.events.component.DurationBarChart
import de.aarondietz.beetmeister.ui.feature.events.component.SystemEventRow
import de.aarondietz.beetmeister.ui.feature.events.component.WateringEventRow

@Composable
internal fun EventsScreen(
    state: BeetRepositoryState,
    onLoadDetails: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = rememberBeetStringResolver()
    var window by remember { mutableStateOf(WateringWindow.Day) }
    var filter by remember { mutableStateOf(EventFilter.All) }
    val nowSeconds = System.currentTimeMillis() / 1000L
    // The repository state is re-emitted on every telemetry tick; keep grouping/sorting work off
    // recomposition by memoizing it on the data it actually depends on.
    val totals = remember(state.recentEvents, window) { wateringTotals(state.recentEvents, window, nowSeconds) }
    val systemSections = remember(state.systemEvents, filter, state.deviceState?.bootId, strings) {
        groupSystemEvents(state.systemEvents.filter { filter.acceptsSystem(it) }, state, strings)
    }
    val isRefreshing = state.eventsLoading || state.eventSync.active

    BeetPullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        enabled = state.connection.phase == BeetConnectionPhase.Connected,
        modifier = modifier,
    ) {
        BeetLazyColumn {
            item {
                BeetCard(
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                ) {
                    SectionHeading(
                        title = strings.get(R.string.events_watering_summary_title),
                        subtitle = strings.get(R.string.events_watering_summary_subtitle),
                    )
                    Spacer(modifier = Modifier.height(MaterialTheme.spacing.headerGap))
                    Text(strings.get(R.string.events_watering_time_by_pair), style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(MaterialTheme.spacing.headerGap))
                    Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.elementGap)) {
                        WateringWindow.entries.forEach { option ->
                            FilterChip(
                                selected = window == option,
                                onClick = { window = option },
                                label = { Text(strings.get(option.labelRes)) },
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(MaterialTheme.spacing.elementGap))
                    DurationBarChart(totals)
                    Spacer(modifier = Modifier.height(MaterialTheme.spacing.elementGap))
                    if (state.eventSync.active) {
                        Text(
                            strings.get(R.string.events_synced_count, state.eventSync.transferred, state.eventSync.total),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(MaterialTheme.spacing.elementGap))
                    }
                    Button(onClick = onLoadDetails) { Text(strings.get(R.string.events_open_watering_history)) }
                }
            }
            item {
                BeetCard(
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                ) {
                    SectionHeading(
                        title = strings.get(R.string.events_system_activity_title),
                        subtitle = strings.get(R.string.events_system_activity_subtitle),
                    )
                    Spacer(modifier = Modifier.height(MaterialTheme.spacing.headerGap))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.elementGap),
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.elementGap),
                    ) {
                            systemEventFilters().forEach { option ->
                                FilterChip(
                                    selected = filter == option,
                                    onClick = { filter = option },
                                    label = { Text(strings.get(option.labelRes)) },
                                )
                            }
                        }
                }
            }
            if (systemSections.isEmpty()) {
                item {
                    EmptySectionCard(
                        title = strings.get(R.string.events_system_empty_title),
                        body = strings.get(R.string.events_system_empty_body),
                        tone = Color(0xFFF3F7FA),
                    )
                }
            } else {
                // One lazy item per event with a pinned section header per group. Rendering each
                // section as a single card composed every event at once and stalled for seconds.
                systemSections.forEach { section ->
                    stickyHeader(key = "sec:${section.key}") {
                        SystemEventSectionHeading(
                            title = section.title,
                            count = section.events.size,
                            strings = strings,
                        )
                    }
                    items(section.events, key = { event -> "sys:${section.key}:${event.bootId}:${event.sequenceNumber}" }) { event ->
                        SystemEventRow(event = event)
                    }
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
    val strings = rememberBeetStringResolver()
    val wateringEvents = remember(state.recentEvents) {
        state.recentEvents.sortedWith(
            compareByDescending<BeetWateringEvent> { it.bootId }.thenByDescending { it.sequenceNumber },
        )
    }
    BeetPullToRefreshBox(
        isRefreshing = state.eventsLoading || state.eventSync.active,
        onRefresh = onReload,
        enabled = state.connection.phase == BeetConnectionPhase.Connected,
        modifier = modifier,
    ) {
        BeetLazyColumn {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                    TextButton(onClick = onBack) { Text(strings.get(R.string.common_back)) }
                }
            }
            item {
                SectionHeading(
                    title = strings.get(R.string.events_watering_history_title),
                    subtitle = strings.get(R.string.events_watering_history_subtitle, wateringEvents.size),
                )
            }
            if (wateringEvents.isEmpty()) {
                item {
                    EmptySectionCard(
                        title = strings.get(R.string.events_watering_empty_title),
                        body = strings.get(R.string.events_watering_empty_body),
                        tone = Color(0xFFF8F3EA),
                    )
                }
            } else {
                items(wateringEvents, key = { event -> "wat${event.bootId}_${event.sequenceNumber}" }) { event ->
                    WateringEventRow(event = event, state = state)
                }
            }
        }
    }
}

internal enum class WateringWindow(@StringRes val labelRes: Int, val seconds: Long) {
    Day(R.string.events_window_day, 24L * 60L * 60L),
    Week(R.string.events_window_week, 7L * 24L * 60L * 60L),
}

internal enum class EventFilter(@StringRes val labelRes: Int) {
    All(R.string.events_filter_all),
    System(R.string.events_filter_system),
    Watering(R.string.events_filter_watering),
    Bluetooth(R.string.events_filter_bluetooth),
    Sleep(R.string.events_filter_sleep),
    Startup(R.string.events_filter_startup),
    MQTT(R.string.events_filter_mqtt),
    Ota(R.string.events_filter_ota),
}

@Composable
private fun SectionHeading(
    title: String,
    subtitle: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SystemEventSectionHeading(
    title: String,
    count: Int,
    strings: BeetStringResolver,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, contentColor = Color(0xFF31566B)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = strings.get(R.string.events_section_event_count, count),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptySectionCard(
    title: String,
    body: String,
    tone: Color,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = tone),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
