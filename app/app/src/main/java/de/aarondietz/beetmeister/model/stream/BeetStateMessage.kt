package de.aarondietz.beetmeister.model.stream

import de.aarondietz.beetmeister.model.controller.BeetDeviceState
import de.aarondietz.beetmeister.model.controller.BeetPairState
import de.aarondietz.beetmeister.model.event.BeetSystemEvent
import de.aarondietz.beetmeister.model.event.BeetWateringEvent

sealed interface BeetStateMessage {
    data class DeviceStateUpdate(val data: BeetDeviceState) : BeetStateMessage

    data class PairStateUpdate(val data: BeetPairState) : BeetStateMessage

    data class SystemEventUpdate(val data: BeetSystemEvent) : BeetStateMessage

    /** Single watering record delivered by the runtime stream_events pump. */
    data class WateringEventUpdate(val data: BeetWateringEvent) : BeetStateMessage

    /** Terminal frame of a stream_events burst (complete or cancelled). */
    data class StreamEndUpdate(val data: BeetStreamEnd) : BeetStateMessage
}
