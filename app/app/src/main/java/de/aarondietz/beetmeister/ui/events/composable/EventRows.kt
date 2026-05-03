package de.aarondietz.beetmeister.ui.events.composable

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
import de.aarondietz.beetmeister.beet.model.event.BeetEventMappings
import de.aarondietz.beetmeister.beet.model.event.BeetSystemEvent
import de.aarondietz.beetmeister.beet.model.event.BeetWateringEvent
import de.aarondietz.beetmeister.beet.model.repository.BeetRepositoryState
import de.aarondietz.beetmeister.ui.composable.ValueGridRow
import de.aarondietz.beetmeister.ui.events.formatSystemEventTime
import de.aarondietz.beetmeister.ui.events.formatWateringTime
import de.aarondietz.beetmeister.ui.events.systemEventPeerLabel
import de.aarondietz.beetmeister.ui.events.systemEventReasonLabel
import de.aarondietz.beetmeister.ui.formatting.formatDuration
import androidx.compose.ui.unit.dp

@Composable
internal fun SystemEventRow(event: BeetSystemEvent, state: BeetRepositoryState) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = Color(0xFFFDFBF6)),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Seq ${event.sequenceNumber}", fontWeight = FontWeight.SemiBold)
                Text(BeetEventMappings.systemEventLabel(event.eventType))
            }
            Spacer(modifier = Modifier.height(8.dp))
            ValueGridRow("Time", formatSystemEventTime(event, state), "Battery", "${event.batteryMillivolts} mV")
            ValueGridRow("Reason", systemEventReasonLabel(event), "Client", systemEventPeerLabel(event))
        }
    }
}

@Composable
internal fun WateringEventRow(event: BeetWateringEvent, state: BeetRepositoryState) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = Color(0xFFF7F3EA)),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Seq ${event.sequenceNumber}", fontWeight = FontWeight.SemiBold)
                Text("Pair ${event.pairIndex}")
            }
            Spacer(modifier = Modifier.height(8.dp))
            ValueGridRow("Source", BeetEventMappings.triggerSourceLabel(event.triggerSource), "Actual", formatDuration(event.actualDurationSeconds))
            ValueGridRow("Requested", formatDuration(event.requestedDurationSeconds), "Stop", BeetEventMappings.stopReasonLabel(event.stopReason))
            ValueGridRow("Block", BeetEventMappings.blockReasonLabel(event.blockReason), "Time", formatWateringTime(event, state))
            ValueGridRow("Before", "${event.moistureBeforePercent}% / ${event.sensorBeforeMillivolts} mV", "After", "${event.moistureAfterPercent}% / ${event.sensorAfterMillivolts} mV")
        }
    }
}
