package de.aarondietz.beetmeister.ui.feature.events.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.aarondietz.beetmeister.R
import de.aarondietz.beetmeister.model.event.BeetSystemEvent
import de.aarondietz.beetmeister.model.event.BeetWateringEvent
import de.aarondietz.beetmeister.model.repository.BeetRepositoryState
import de.aarondietz.beetmeister.strings.rememberBeetStringResolver
import de.aarondietz.beetmeister.ui.core.component.ValueGridRow
import de.aarondietz.beetmeister.ui.core.formatting.eventBlockReasonLabel
import de.aarondietz.beetmeister.ui.core.formatting.formatDuration
import de.aarondietz.beetmeister.ui.core.formatting.formatPercentAndMillivolts
import de.aarondietz.beetmeister.ui.core.formatting.stopReasonLabel
import de.aarondietz.beetmeister.ui.core.formatting.triggerSourceLabel
import de.aarondietz.beetmeister.ui.feature.events.formatWateringTime
import de.aarondietz.beetmeister.ui.feature.events.systemEventCategoryLabel
import de.aarondietz.beetmeister.ui.feature.events.systemEventCompactDetails
import de.aarondietz.beetmeister.ui.feature.events.formatSystemEventTimelineTime
import de.aarondietz.beetmeister.ui.feature.events.systemEventTitle

@Composable
internal fun SystemEventRow(event: BeetSystemEvent) {
    val strings = rememberBeetStringResolver()
    val accent = systemEventAccent(event)
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = accent.copy(alpha = 0.14f)),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = systemEventTitle(event, strings),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = formatSystemEventTimelineTime(event, strings),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Surface(
                    color = accent.copy(alpha = 0.18f),
                    contentColor = accent.copy(alpha = 0.95f),
                    shape = RoundedCornerShape(999.dp),
                ) {
                    Text(
                        text = systemEventCategoryLabel(event, strings),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = systemEventCompactDetails(event, strings),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = strings.get(R.string.common_sequence_number, event.sequenceNumber),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun WateringEventRow(event: BeetWateringEvent, state: BeetRepositoryState) {
    val strings = rememberBeetStringResolver()
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = Color(0xFFF7F3EA)),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(strings.get(R.string.common_sequence_number, event.sequenceNumber), fontWeight = FontWeight.SemiBold)
                Text(strings.get(R.string.common_pair_number, event.pairIndex))
            }
            Spacer(modifier = Modifier.height(8.dp))
            ValueGridRow(
                strings.get(R.string.events_label_source),
                triggerSourceLabel(event.triggerSource, strings),
                strings.get(R.string.events_label_actual),
                formatDuration(event.actualDurationSeconds, strings),
            )
            ValueGridRow(
                strings.get(R.string.events_label_requested),
                formatDuration(event.requestedDurationSeconds, strings),
                strings.get(R.string.events_label_stop),
                stopReasonLabel(event.stopReason, strings),
            )
            ValueGridRow(
                strings.get(R.string.events_label_block),
                eventBlockReasonLabel(event.blockReason, strings),
                strings.get(R.string.events_label_time),
                formatWateringTime(event, state, strings),
            )
            ValueGridRow(
                strings.get(R.string.events_label_before),
                formatPercentAndMillivolts(event.moistureBeforePercent, event.sensorBeforeMillivolts, strings),
                strings.get(R.string.events_label_after),
                formatPercentAndMillivolts(event.moistureAfterPercent, event.sensorAfterMillivolts, strings),
            )
        }
    }
}

private fun systemEventAccent(event: BeetSystemEvent): Color = when {
    event.eventType.startsWith("BLE_") -> Color(0xFF2A6B7E)
    event.eventType.startsWith("MQTT_") -> Color(0xFF3F6B4E)
    event.eventType.startsWith("OTA_") -> Color(0xFF8A5A26)
    event.eventType == "SLEEP" -> Color(0xFF6B5C8F)
    event.eventType == "STARTUP" -> Color(0xFF8A3B52)
    else -> Color(0xFF55616E)
}
