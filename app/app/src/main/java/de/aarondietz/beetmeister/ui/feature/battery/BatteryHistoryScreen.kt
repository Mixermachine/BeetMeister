package de.aarondietz.beetmeister.ui.feature.battery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.aarondietz.beetmeister.R
import de.aarondietz.beetmeister.model.event.BeetSystemEvent
import de.aarondietz.beetmeister.model.repository.BeetRepositoryState
import de.aarondietz.beetmeister.strings.rememberBeetStringResolver
import de.aarondietz.beetmeister.ui.core.component.BeetCard
import de.aarondietz.beetmeister.ui.core.component.BeetLazyColumn
import de.aarondietz.beetmeister.ui.core.formatting.batteryStateLabel
import de.aarondietz.beetmeister.ui.core.formatting.formatMillivolts
import de.aarondietz.beetmeister.ui.core.formatting.formatPercent
import de.aarondietz.beetmeister.ui.core.theme.spacing

/**
 * Modern Material 3 Battery voltage history screen.
 *
 * Displays:
 * 1. Current status card (large voltage readout, percentage, battery state, min/avg/max stats).
 * 2. Interactive native Compose Canvas voltage trend chart with smooth curves and touch scrubbing.
 * 3. Recent reading entries card with timestamp and event details.
 */
@Composable
fun BatteryHistoryScreen(
    state: BeetRepositoryState,
    modifier: Modifier = Modifier,
) {
    val strings = rememberBeetStringResolver()
    val events = state.systemEvents
    val device = state.deviceState

    var scrubbedPoint by remember { mutableStateOf<BatteryChartPoint?>(null) }

    val chartPoints = remember(events) {
        buildBatteryPoints(events)
    }

    BeetLazyColumn(modifier = modifier) {
        if (chartPoints.size < 2) {
            item {
                BeetCard {
                    Text(
                        text = strings.get(R.string.battery_history_insufficient_data),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp, horizontal = 16.dp),
                    )
                }
            }
            return@BeetLazyColumn
        }

        val minMv = chartPoints.minOf { it.millivolts }
        val maxMv = chartPoints.maxOf { it.millivolts }
        val avgMv = chartPoints.map { it.millivolts }.average().toInt()

        // 1. Current KPI Status Card
        item {
            BeetCard {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.elementGap),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            val activeMv = scrubbedPoint?.millivolts ?: device?.batteryMillivolts ?: chartPoints.last().millivolts
                            val activeLabel = scrubbedPoint?.let { "${it.timestampLabel} (Scrubbed)" } ?: strings.get(R.string.battery_history_current_voltage)

                            Text(
                                text = activeLabel,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(
                                verticalAlignment = Alignment.Bottom,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = formatMillivolts(activeMv, strings),
                                    style = MaterialTheme.typography.headlineMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                device?.let {
                                    Text(
                                        text = "(${formatPercent(it.batteryPercentApprox, strings)})",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(bottom = 2.dp),
                                    )
                                }
                            }
                        }

                        device?.let {
                            Text(
                                text = batteryStateLabel(it.batteryState, strings),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    // Secondary metrics row: Min | Avg | Max | Count
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        MetricItem(
                            label = strings.get(R.string.battery_history_min),
                            value = formatMillivolts(minMv, strings),
                        )
                        MetricItem(
                            label = strings.get(R.string.battery_history_avg),
                            value = formatMillivolts(avgMv, strings),
                        )
                        MetricItem(
                            label = strings.get(R.string.battery_history_max),
                            value = formatMillivolts(maxMv, strings),
                        )
                        MetricItem(
                            label = strings.get(R.string.battery_history_samples_count, chartPoints.size),
                            value = "${chartPoints.size}",
                        )
                    }
                }
            }
        }

        // 2. Interactive Native Line Chart Card
        item {
            BeetCard {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.elementGap),
                ) {
                    Text(
                        text = strings.get(R.string.battery_history_chart_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    NativeBatteryLineChart(
                        points = chartPoints,
                        onScrubPoint = { scrubbedPoint = it },
                    )
                }
            }
        }

        // 3. Recent Readings Log Card
        item {
            BeetCard {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.elementGap),
                ) {
                    Text(
                        text = strings.get(R.string.battery_history_recent_readings),
                        style = MaterialTheme.typography.titleMedium,
                    )

                    val recentReadings = chartPoints.takeLast(6).reversed()
                    recentReadings.forEachIndexed { index, point ->
                        if (index > 0) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Text(
                                    text = point.timestampLabel,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    text = strings.get(R.string.common_sequence_number, point.sequenceNumber),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                text = formatMillivolts(point.millivolts, strings),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricItem(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

internal fun buildBatteryPoints(events: List<BeetSystemEvent>): List<BatteryChartPoint> {
    return events
        .filter { it.batteryMillivolts > 0 }
        .sortedBy { it.sequenceNumber }
        .map { event ->
            val label = if (event.unixSeconds > 0L) {
                val totalSeconds = event.unixSeconds % (24 * 60 * 60)
                val hours = totalSeconds / 3600
                val minutes = (totalSeconds % 3600) / 60
                "${hours.toString().padStart(2, '0')}:${minutes.toString().padStart(2, '0')}"
            } else {
                "${event.uptimeSeconds}s"
            }
            BatteryChartPoint(
                sequenceNumber = event.sequenceNumber,
                timestampLabel = label,
                millivolts = event.batteryMillivolts,
            )
        }
}
