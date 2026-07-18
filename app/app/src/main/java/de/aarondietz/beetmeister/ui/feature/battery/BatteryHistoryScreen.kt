package de.aarondietz.beetmeister.ui.feature.battery

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.himanshoe.charty.color.ChartyColor
import com.himanshoe.charty.color.ChartyColors
import com.himanshoe.charty.common.config.Animation
import com.himanshoe.charty.line.LineChart
import com.himanshoe.charty.line.config.LineChartConfig
import com.himanshoe.charty.line.data.LineData
import de.aarondietz.beetmeister.model.event.BeetSystemEvent
import de.aarondietz.beetmeister.model.repository.BeetRepositoryState
import de.aarondietz.beetmeister.ui.core.formatting.formatMillivolts
import de.aarondietz.beetmeister.strings.rememberBeetStringResolver
import de.aarondietz.beetmeister.R

/**
 * Battery voltage history chart.
 *
 * Renders every [BeetSystemEvent]'s battery voltage over time as a line
 * chart. X-axis = event time label; Y-axis = millivolts. If fewer than
 * two events are available, shows a placeholder message instead.
 */
@Composable
fun BatteryHistoryScreen(
    state: BeetRepositoryState,
    modifier: Modifier = Modifier,
) {
    val strings = rememberBeetStringResolver()
    val events = state.systemEvents

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Text(
            text = strings.get(R.string.battery_history_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(bottom = 16.dp),
        )

        if (events.size < 2) {
            Text(
                text = strings.get(R.string.battery_history_insufficient_data),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 48.dp),
            )
            return@Column
        }

        val chartData = remember(events) {
            buildBatteryChartData(events)
        }

        LineChart(
            data = { chartData },
            color = ChartyColor.Solid(ChartyColors.Blue),
            lineConfig = LineChartConfig(
                lineWidth = 2f,
                showPoints = true,
                pointRadius = 4f,
                smoothCurve = false,
                animation = Animation.Default,
                tooltipFormatter = { data ->
                    formatMillivolts(data.value.toInt(), strings)
                },
            ),
            onPointClick = null,
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp),
        )
    }
}

/**
 * Builds [LineData] points from system events, sorted by sequence number.
 *
 * Each point uses a short time label:
 * - If [BeetSystemEvent.unixSeconds] is valid (>0), formats "HH:MM"
 * - Otherwise uses [BeetSystemEvent.uptimeSeconds] as "##s"
 *
 * Duplicate voltages at the same value are not deduplicated — the chart
 * handles flat segments naturally.
 */
internal fun buildBatteryChartData(events: List<BeetSystemEvent>): List<LineData> {
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
            LineData(
                label = label,
                value = event.batteryMillivolts.toFloat(),
            )
        }
}
