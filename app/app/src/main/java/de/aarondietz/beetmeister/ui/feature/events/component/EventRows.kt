package de.aarondietz.beetmeister.ui.feature.events.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import de.aarondietz.beetmeister.R
import de.aarondietz.beetmeister.model.event.BeetSystemEvent
import de.aarondietz.beetmeister.model.event.BeetWateringEvent
import de.aarondietz.beetmeister.model.repository.BeetRepositoryState
import de.aarondietz.beetmeister.strings.rememberBeetStringResolver
import de.aarondietz.beetmeister.ui.core.component.ValueGridRow
import de.aarondietz.beetmeister.ui.core.formatting.eventBlockReasonLabel
import de.aarondietz.beetmeister.ui.core.formatting.formatDuration
import de.aarondietz.beetmeister.ui.core.formatting.formatMillivolts
import de.aarondietz.beetmeister.ui.core.formatting.formatPercentAndMillivolts
import de.aarondietz.beetmeister.ui.core.formatting.stopReasonLabel
import de.aarondietz.beetmeister.ui.core.formatting.systemEventLabel
import de.aarondietz.beetmeister.ui.core.formatting.triggerSourceLabel
import de.aarondietz.beetmeister.ui.feature.events.formatSystemEventTime
import de.aarondietz.beetmeister.ui.feature.events.formatWateringTime
import de.aarondietz.beetmeister.ui.feature.events.systemEventPeerLabel
import de.aarondietz.beetmeister.ui.feature.events.systemEventReasonLabel
import androidx.compose.ui.unit.dp

@Composable
internal fun SystemEventRow(event: BeetSystemEvent, state: BeetRepositoryState) {
    val strings = rememberBeetStringResolver()
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = Color(0xFFFDFBF6)),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(strings.get(R.string.common_sequence_number, event.sequenceNumber), fontWeight = FontWeight.SemiBold)
                Text(systemEventLabel(event.eventType, strings))
            }
            Spacer(modifier = Modifier.height(8.dp))
            ValueGridRow(
                strings.get(R.string.events_label_time),
                formatSystemEventTime(event, state, strings),
                strings.get(R.string.events_label_battery),
                formatMillivolts(event.batteryMillivolts, strings),
            )
            ValueGridRow(
                strings.get(R.string.events_label_reason),
                systemEventReasonLabel(event, strings),
                strings.get(R.string.events_label_client),
                systemEventPeerLabel(event, strings),
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
