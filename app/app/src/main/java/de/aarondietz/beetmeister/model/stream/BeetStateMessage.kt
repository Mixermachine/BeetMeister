package de.aarondietz.beetmeister.model.stream

import de.aarondietz.beetmeister.model.controller.BeetDeviceState
import de.aarondietz.beetmeister.model.controller.BeetPairState
import de.aarondietz.beetmeister.model.event.BeetSystemEvent

sealed interface BeetStateMessage {
    data class DeviceStateUpdate(val data: BeetDeviceState) : BeetStateMessage

    data class PairStateUpdate(val data: BeetPairState) : BeetStateMessage

    data class SystemEventUpdate(val data: BeetSystemEvent) : BeetStateMessage
}
